package io.inspector.api;

import io.inspector.audit.AuditService;
import io.inspector.security.Role;
import io.inspector.security.mapping.AccessMappingAdminService;
import io.inspector.security.mapping.FleetGrant;
import io.inspector.security.mapping.GrantChange;
import io.inspector.security.mapping.MappingSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The group→scope mapping admin surface (IDP-SECURITY.md §10, R-SAFE-14) — the APEX surface, above
 * Registry CRUD. Every route is {@code @PreAuthorize("@rbac.canAdministerAccess(authentication)")}
 * at the door AND the service re-checks. Reads work in both file/db modes (the mapping seam);
 * writes require the DB store — under {@code mapping-source: file} the write service bean is absent
 * and writes 409. Grants are value tuples (edit = remove + add), so add/remove address them by tuple.
 *
 * <p>The access-review READ is itself a security-relevant disclosure (a recon oracle) and is audited
 * (§7). Widening writes return a {@code proposed} outcome with the computed eligible-approver set;
 * a second independent ACCESS_ADMIN approves via {@code /proposals/{id}/approve}.
 */
@RestController
@RequestMapping("/api/admin/access")
@PreAuthorize("@rbac.canAdministerAccess(authentication)")
public class AdminAccessController {

    private final MappingSource mappingSource;
    private final ObjectProvider<AccessMappingAdminService> service; // absent under mapping-source: file
    private final AuditService audit;

    public AdminAccessController(
            MappingSource mappingSource, ObjectProvider<AccessMappingAdminService> service, AuditService audit) {
        this.mappingSource = mappingSource;
        this.service = service;
        this.audit = audit;
    }

    /** The full mapping (ladder + fleet grants, source-tagged). ACCESS_ADMIN. Audited read. */
    @GetMapping
    public AccessMappingDto list(Authentication authentication) {
        audit.recordConfigEvent("access-read", authentication.getName(), true, Map.of("surface", "mapping"));
        return new AccessMappingDto(
                mappingSource.allLadderGrants().stream()
                        .map(r -> new LadderView(r.group(), r.role().name(), r.engineId(), r.tenantId(), r.source()))
                        .toList(),
                mappingSource.allFleetGrants().stream()
                        .map(r -> new FleetView(r.group(), r.grant().name(), r.source()))
                        .toList());
    }

    @PostMapping("/grants")
    public AccessMappingAdminService.Outcome add(@RequestBody GrantRequest body, Authentication authentication) {
        return translate(() -> writes().submit(body.toChange(true), body.requireReason(), authentication));
    }

    @DeleteMapping("/grants")
    public AccessMappingAdminService.Outcome remove(@RequestBody GrantRequest body, Authentication authentication) {
        return translate(() -> writes().submit(body.toChange(false), body.requireReason(), authentication));
    }

    @PostMapping("/proposals/{id}/approve")
    public AccessMappingAdminService.Outcome approve(@PathVariable long id, Authentication authentication) {
        return translate(() -> writes().approve(id, authentication));
    }

    /** DB-vs-mounted-file drift; hard-alerts if a file-pin would leave no ACCESS_ADMIN. ACCESS_ADMIN. */
    @GetMapping("/drift")
    public AccessMappingAdminService.DriftReport drift() {
        return writes().drift();
    }

    @GetMapping("/proposals")
    public List<ProposalView> proposals() {
        return writes().pendingProposals().stream()
                .map(p -> new ProposalView(
                        p.getId(),
                        p.getProposer(),
                        p.getGroupName(),
                        p.getSummary(),
                        p.getReason(),
                        p.getExpiresAt().toString()))
                .toList();
    }

    private AccessMappingAdminService writes() {
        AccessMappingAdminService svc = service.getIfAvailable();
        if (svc == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "mapping is file-pinned (mapping-source: file) — CRUD is disabled; edit the mounted file or "
                            + "switch to the DB store.");
        }
        return svc;
    }

    /** Map the service's typed governance failures onto HTTP without leaking internals as 500s. */
    private <T> T translate(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (AccessMappingAdminService.IneligibleApproverException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (AccessMappingAdminService.CrudDisabledException
                | AccessMappingAdminService.ApexInvariantException
                | AccessMappingAdminService.NoEligibleApproverException
                | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /* -------------------- DTOs -------------------- */

    public record AccessMappingDto(List<LadderView> ladderGrants, List<FleetView> fleetGrants) {}

    public record LadderView(String group, String role, String engineId, String tenantId, String source) {}

    public record FleetView(String group, String grant, String source) {}

    public record ProposalView(
            Long id, String proposer, String group, String summary, String reason, String expiresAt) {}

    public record GrantRequest(
            String type, // "ladder" | "fleet"
            @NotBlank String group,
            String role, // ladder
            String engineId, // ladder
            String tenantId, // ladder
            String fleetGrant, // fleet: REGISTRY_ADMIN | ACCESS_ADMIN
            @Size(min = 10, max = 500) String reason) {

        String requireReason() {
            if (reason == null || reason.strip().length() < 10) {
                throw new IllegalArgumentException("a reason of at least 10 characters is required");
            }
            return reason;
        }

        GrantChange toChange(boolean add) {
            if ("fleet".equalsIgnoreCase(type)) {
                FleetGrant fg = parseFleet();
                return add ? GrantChange.fleetAdd(group, fg) : GrantChange.fleetRemove(group, fg);
            }
            Role r = parseRole();
            String engine = engineId != null && !engineId.isBlank() ? engineId : "*";
            String tenant = tenantId != null && !tenantId.isBlank() ? tenantId : "*";
            return add
                    ? GrantChange.ladderAdd(group, r, engine, tenant)
                    : GrantChange.ladderRemove(group, r, engine, tenant);
        }

        private Role parseRole() {
            try {
                return Role.valueOf(role);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("invalid ladder role: " + role);
            }
        }

        private FleetGrant parseFleet() {
            try {
                return FleetGrant.valueOf(fleetGrant);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("invalid fleet grant: " + fleetGrant);
            }
        }
    }
}

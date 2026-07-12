package io.inspector.api;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.RegistryProperties;
import io.inspector.registry.EngineRegistryMapper;
import io.inspector.registry.EngineRegistryRow;
import io.inspector.registry.EngineRegistryStore;
import io.inspector.registry.RegistryDrift.DriftReport;
import io.inspector.registry.RegistryPinRegistry;
import io.inspector.registry.RegistryWrite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The runtime engine-registry admin surface (docs/REGISTRY-CRUD.md §9, R-SAFE-13) — the
 * highest-privilege surface in the tool. EVERY route is gated
 * {@code @PreAuthorize("@rbac.canAdministerRegistry(authentication)")} at the door AND the store
 * re-checks fail-closed audit + SSRF validation in the service. Distinct from the secret-free,
 * enabled-only {@code GET /api/engines}: this lists draft/disabled/tombstoned rows too and shows
 * secret-ref PRESENCE (never values).
 *
 * <p>Trust is earned: add → DRAFT (read-only), probe (read-only) → PROBED, enable → ACTIVE. A
 * prod enable-read-write, a remove, and a purge require a typed token (the engine id) AND a
 * four-eyes proposal (R-SAFE-08, #91): the write returns {@code applied} immediately, or
 * {@code proposed} — parked until a second independent REGISTRY_ADMIN approves via
 * {@code POST /proposals/{id}/approve}. These three writes are NOT (yet) folded into the
 * dangerous-set re-auth gate ({@code DangerousActionReauthGate}) — that gate's dangerous set is
 * scoped to tier-3 action verbs + bulk + mapping writes (IDP-SECURITY.md §5); registry CRUD sits
 * behind its own orthogonal REGISTRY_ADMIN grant + typed token + (now) four-eyes instead. Revisit
 * together if the two dangerous-set definitions should ever merge.
 */
@RestController
@RequestMapping("/api/admin/engines")
@PreAuthorize("@rbac.canAdministerRegistry(authentication)")
public class AdminEnginesController {

    private final EngineRegistryStore store;
    private final InspectorProperties inspectorProperties;
    private final RegistryProperties registryProperties;
    private final RegistryPinRegistry pinRegistry;
    private final ProcessApiClient processApi;
    private final Environment env;

    public AdminEnginesController(
            EngineRegistryStore store,
            InspectorProperties inspectorProperties,
            RegistryProperties registryProperties,
            RegistryPinRegistry pinRegistry,
            ProcessApiClient processApi,
            Environment env) {
        this.store = store;
        this.inspectorProperties = inspectorProperties;
        this.registryProperties = registryProperties;
        this.pinRegistry = pinRegistry;
        this.processApi = processApi;
        this.env = env;
    }

    @GetMapping
    public List<AdminEngineDto> list() {
        return store.findAllForAdmin().stream().map(this::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminEngineDto add(@Valid @RequestBody EngineWriteRequest body, Authentication authentication) {
        assertNotConfigPinned();
        return toDto(store.add(body.toWrite(body.id()), authentication, body.reason()));
    }

    @PutMapping("/{id}")
    public AdminEngineDto edit(
            @PathVariable String id, @Valid @RequestBody EngineWriteRequest body, Authentication authentication) {
        assertNotConfigPinned();
        return toDto(store.edit(id, body.toWrite(id), authentication, body.reason()));
    }

    /** Run the READ-ONLY probe now (version/reachability). Failures are coarse to the UI (oracle). */
    @PostMapping("/{id}/probe")
    public AdminEngineDto probe(@PathVariable String id, Authentication authentication) {
        assertNotConfigPinned();
        EngineRegistryRow row = store.requireRow(id);

        // SSRF re-validation at the DIAL point (Gemini S4 review): add/edit validate at WRITE, but the
        // probe is a live dial surface, so re-validate here — it RE-RESOLVES, so a base-URL that was
        // validated at add but has since rebound to an internal address is rejected before we dial.
        // Routes through RegistryPinRegistry so a successful re-validation ALSO re-pins the host for
        // connect-time reuse by the health loop / operation dials (R-OPS-13, #91).
        EngineEnvironment environment =
                EngineEnvironment.valueOf(row.getEnvironment().toUpperCase(Locale.ROOT));
        if (!pinRegistry
                .validate(row.getBaseUrl(), environment, registryProperties.egressPolicy())
                .isAllowed()) {
            // Coarse to the UI (no oracle); the rejecting rail is in the store's log + audit.
            return toDto(store.recordProbe(id, false, authentication, "ssrf re-validation failed at probe"));
        }

        boolean ok;
        String detail;
        try {
            var info = processApi.engineInfo(engine(row), CallPriority.INTERACTIVE);
            ok = info != null;
            detail = "version=" + (info != null ? info.get("version") : "?");
        } catch (RuntimeException e) {
            ok = false;
            detail = e.toString(); // audit only — never surfaced (topology oracle)
        }
        return toDto(store.recordProbe(id, ok, authentication, detail));
    }

    private static EngineConfig engine(EngineRegistryRow row) {
        return EngineRegistryMapper.toEngineConfig(row);
    }

    @PostMapping("/{id}/enable")
    public EngineWriteOutcome enable(
            @PathVariable String id, @Valid @RequestBody EnableRequest body, Authentication authentication) {
        assertNotConfigPinned();
        EngineRegistryRow row = store.requireRow(id);
        boolean readWrite = body.readWrite() != null && body.readWrite();
        // Prod read-write enable is the dangerous flip: typed token = the engine id, PLUS four-eyes
        // (the store parks it as a proposal — see class doc).
        if (readWrite && "prod".equals(row.getEnvironment())) {
            requireTypedToken(id, body.confirmToken());
        }
        return toOutcome(store.enable(id, readWrite, authentication, body.reason()));
    }

    @PostMapping("/{id}/disable")
    public AdminEngineDto disable(
            @PathVariable String id, @Valid @RequestBody ReasonRequest body, Authentication authentication) {
        assertNotConfigPinned();
        return toDto(store.disable(id, authentication, body.reason()));
    }

    @DeleteMapping("/{id}")
    public EngineWriteOutcome remove(
            @PathVariable String id, @Valid @RequestBody ConfirmRequest body, Authentication authentication) {
        assertNotConfigPinned();
        requireTypedToken(id, body.confirmToken()); // remove always requires the typed id, PLUS four-eyes
        return toOutcome(store.remove(id, authentication, body.reason()));
    }

    @PostMapping("/{id}/purge")
    public EngineWriteOutcome purge(
            @PathVariable String id, @Valid @RequestBody ConfirmRequest body, Authentication authentication) {
        assertNotConfigPinned();
        requireTypedToken(id, body.confirmToken()); // PLUS four-eyes
        return toOutcome(store.purge(id, authentication, body.reason()));
    }

    /** The pending four-eyes inbox (R-SAFE-08). REGISTRY_ADMIN. */
    @GetMapping("/proposals")
    public List<EngineProposalView> proposals() {
        return store.pendingProposals().stream()
                .map(p -> new EngineProposalView(
                        p.getId(),
                        p.getProposer(),
                        p.getEngineId(),
                        p.getKind(),
                        p.getSummary(),
                        p.getReason(),
                        p.getExpiresAt().toString()))
                .toList();
    }

    /** A second independent REGISTRY_ADMIN approves a pending proposal, which then applies. */
    @PostMapping("/proposals/{id}/approve")
    public EngineWriteOutcome approve(@PathVariable long id, Authentication authentication) {
        assertNotConfigPinned();
        return toOutcome(translate(() -> store.approve(id, authentication)));
    }

    /** DB-vs-YAML drift (the admin badge / access-review). REGISTRY_ADMIN. */
    @GetMapping("/drift")
    public DriftReport drift() {
        return store.driftReport(inspectorProperties.engines());
    }

    /* ---------------- guards ---------------- */

    private void assertNotConfigPinned() {
        if (registryProperties.isConfigPinned()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "registry is config-pinned (inspector.registry.source=config) — CRUD is disabled");
        }
    }

    private void requireTypedToken(String id, String confirmToken) {
        if (!id.equals(confirmToken)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "type the engine id (\"" + id + "\") to confirm this action. Nothing happened.");
        }
    }

    private AdminEngineDto toDto(EngineRegistryRow row) {
        return new AdminEngineDto(
                row.getId(),
                row.getName(),
                row.getBaseUrl(),
                row.getEnvironment(),
                row.getMode(),
                row.getLifecycle(),
                row.getAccentColor(),
                row.getTenantId(),
                row.getTelemetryUrlTemplate(),
                row.getAuthType(),
                row.getAuthUsername(),
                row.getPasswordRef(),
                present(row.getPasswordRef()),
                row.getTokenRef(),
                present(row.getTokenRef()),
                row.getConnectMs(),
                row.getReadMs(),
                row.getWriteMs(),
                row.getMaxPageSize(),
                row.getDlqScanCap(),
                row.getSource(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getRemovedAt());
    }

    /** Secret-ref PRESENCE — is the named env var set in THIS deployment? Never the value. */
    private boolean present(String ref) {
        return ref != null && !ref.isBlank() && env.getProperty(ref) != null;
    }

    private EngineWriteOutcome toOutcome(EngineRegistryStore.Outcome outcome) {
        return new EngineWriteOutcome(
                outcome.status(),
                outcome.proposalId(),
                outcome.eligibleApproverGroups(),
                outcome.summary(),
                outcome.row() != null ? toDto(outcome.row()) : null);
    }

    /** Map the store's typed four-eyes failures onto HTTP without leaking internals as 500s. */
    private <T> T translate(Supplier<T> op) {
        try {
            return op.get();
        } catch (EngineRegistryStore.IneligibleApproverException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (EngineRegistryStore.NoEligibleApproverException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /* ---------------- DTOs ---------------- */

    public record AdminEngineDto(
            String id,
            String name,
            String baseUrl,
            String environment,
            String mode,
            String lifecycle,
            String accentColor,
            String tenantId,
            String telemetryUrlTemplate,
            String authType,
            String authUsername,
            String passwordRef,
            boolean passwordRefPresent,
            String tokenRef,
            boolean tokenRefPresent,
            Integer connectMs,
            Integer readMs,
            Integer writeMs,
            Integer maxPageSize,
            Integer dlqScanCap,
            String source,
            Instant createdAt,
            Instant updatedAt,
            Instant removedAt) {}

    public record EngineWriteRequest(
            String id, // POST only (immutable); ignored on PUT
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 2000) String baseUrl,
            EngineEnvironment environment,
            String accentColor,
            String tenantId,
            String telemetryUrlTemplate,
            String authType,
            String authUsername,
            String passwordRef,
            String tokenRef,
            Integer connectMs,
            Integer readMs,
            Integer writeMs,
            Integer maxPageSize,
            Integer dlqScanCap,
            Integer alarmOldestWarnMin,
            Integer alarmOldestCritMin,
            Integer alarmOverdueGraceS,
            @NotBlank @Size(min = 10, max = 500) String reason) {

        RegistryWrite toWrite(String effectiveId) {
            return new RegistryWrite(
                    effectiveId,
                    name,
                    baseUrl,
                    environment,
                    accentColor,
                    tenantId,
                    telemetryUrlTemplate,
                    authType,
                    authUsername,
                    passwordRef,
                    tokenRef,
                    connectMs,
                    readMs,
                    writeMs,
                    maxPageSize,
                    dlqScanCap,
                    alarmOldestWarnMin,
                    alarmOldestCritMin,
                    alarmOverdueGraceS);
        }
    }

    public record ReasonRequest(
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    public record EnableRequest(
            Boolean readWrite,
            String confirmToken,
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    public record ConfirmRequest(
            String confirmToken,
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    /** The outcome of a dangerous write (R-SAFE-08, #91): applied now, or parked as a proposal. */
    public record EngineWriteOutcome(
            String status, // "applied" | "proposed"
            Long proposalId,
            Set<String> eligibleApproverGroups,
            String summary,
            AdminEngineDto engine) {}

    /**
     * Distinct name from {@link AdminAccessController.ProposalView} — both being named "ProposalView"
     * collided into a single wrong-shaped OpenAPI schema (springdoc names schemas by simple class
     * name, not FQN) when they were generated together (#91 review finding).
     */
    public record EngineProposalView(
            Long id, String proposer, String engineId, String kind, String summary, String reason, String expiresAt) {}
}

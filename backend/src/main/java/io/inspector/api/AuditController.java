package io.inspector.api;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditService;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit read surface (SPEC §9): the per-instance Audit tab ("what did the last shift
 * try") and the global operations log. Read-only — writes exist ONLY inside the action
 * rails. Payload bodies are role-gated OPERATOR+ (R-AUD-03); everyone else sees the
 * accountability skeleton (who/when/what/outcome) without the values.
 */
@RestController
@RequestMapping("/api")
public class AuditController {

    private static final int MAX_PAGE = 200;

    private final AuditEntryRepository repository;
    private final RbacAuthorizer rbac;

    public AuditController(AuditEntryRepository repository, RbacAuthorizer rbac) {
        this.repository = repository;
        this.rbac = rbac;
    }

    @GetMapping("/instances/{engineId}/{instanceId}/audit")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public List<AuditEntryDto> instanceAudit(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        boolean payloadVisible = rbac.hasRoleOn(authentication, Role.OPERATOR, engineId);
        return repository
                .findByEngineIdAndInstanceIdOrderByTsDesc(engineId, instanceId, PageRequest.of(0, cap(limit)))
                .stream()
                .map(entry -> AuditEntryDto.from(entry, payloadVisible))
                .toList();
    }

    @GetMapping("/audit")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public List<AuditEntryDto> operationsLog(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String engineId,
            @RequestParam(required = false) String ticketId,
            @RequestParam(required = false) Instant since,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication) {
        boolean adminAnywhere = rbac.atLeast(authentication, "ADMIN");
        return repository
                .findLog(
                        actor,
                        action,
                        engineId,
                        blankToNull(ticketId),
                        since != null ? since : Instant.EPOCH,
                        PageRequest.of(0, cap(limit)))
                .stream()
                .map(entry -> AuditEntryDto.from(entry, payloadVisible(entry, authentication, adminAnywhere)))
                .toList();
    }

    /**
     * Config-event rows (R-AUD-10) carry the {@link AuditService#CONFIG_ENGINE_ID} sentinel, which
     * no operator is scoped to — so the usual per-engine OPERATOR gate would hide them from every
     * engine-scoped admin. They are instead visible to ANY ADMIN (engine-scope-independent); every
     * other row keeps the per-engine OPERATOR+ payload gate.
     */
    private boolean payloadVisible(AuditEntry entry, Authentication authentication, boolean adminAnywhere) {
        if (AuditService.CONFIG_ENGINE_ID.equals(entry.getEngineId())) {
            return adminAnywhere;
        }
        return rbac.hasRoleOn(authentication, Role.OPERATOR, entry.getEngineId());
    }

    private static int cap(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** Wire shape of one audit row; {@code payload} is null when the caller is below OPERATOR. */
    public record AuditEntryDto(
            UUID id,
            String correlationId,
            String actor,
            Instant ts,
            String engineId,
            String tenantId,
            String instanceId,
            String action,
            String reason,
            String ticketId,
            String payload,
            Integer httpStatus,
            String outcome,
            String responseSnippet,
            boolean responseTruncated,
            boolean breakGlass) {

        static AuditEntryDto from(AuditEntry entry, boolean payloadVisible) {
            return new AuditEntryDto(
                    entry.getId(),
                    entry.getCorrelationId(),
                    entry.getActor(),
                    entry.getTs(),
                    entry.getEngineId(),
                    entry.getTenantId(),
                    entry.getInstanceId(),
                    entry.getAction(),
                    entry.getReason(),
                    entry.getTicketId(),
                    payloadVisible ? entry.getPayload() : null,
                    entry.getHttpStatus(),
                    entry.getOutcome().name(),
                    entry.getResponseSnippet(),
                    entry.isResponseTruncated(),
                    entry.isBreakGlass());
        }
    }
}

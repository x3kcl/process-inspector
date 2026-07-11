package io.inspector.api;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditService;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    /** CSV export bounds (R-AUD-08): repository page size and the hard row ceiling. */
    private static final int CSV_CHUNK = 500;

    private static final int CSV_MAX_ROWS = 10_000;

    private static final String CSV_HEADER =
            "ts,actor,action,engineId,tenantId,instanceId,outcome,httpStatus,reason,ticketId,correlationId,breakGlass\n";

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
     * The operations-log CSV export (R-AUD-08, usability W3-1): the SAME filters as
     * {@link #operationsLog}, streamed as {@code text/csv} in repository pages so an export
     * never materializes tens of thousands of rows in memory. Deliberately exports only the
     * accountability skeleton — payload and response-snippet bodies stay role-gated in the
     * app (R-AUD-03). Every text cell is formula-escaped per R-OPS-08 ({@link Csv#cell}).
     */
    @GetMapping(value = "/audit/export", produces = "text/csv")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    @Operation(summary = "Operations log as CSV — same filters as GET /api/audit, skeleton columns only")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string")))
    public ResponseEntity<StreamingResponseBody> operationsLogCsv(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String engineId,
            @RequestParam(required = false) String ticketId,
            @RequestParam(required = false) Instant since,
            @RequestParam(defaultValue = "10000") int limit) {
        String ticket = blankToNull(ticketId);
        Instant sinceOrEpoch = since != null ? since : Instant.EPOCH;
        int max = Math.max(1, Math.min(limit, CSV_MAX_ROWS));
        StreamingResponseBody body = out -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write(CSV_HEADER);
            int written = 0;
            for (int page = 0; written < max; page++) {
                List<AuditEntry> rows = repository.findLog(
                        actor, action, engineId, ticket, sinceOrEpoch, PageRequest.of(page, CSV_CHUNK));
                for (AuditEntry entry : rows) {
                    if (written == max) break;
                    writer.write(csvLine(entry));
                    written++;
                }
                // A short page ends the export: findLog is a single offset-paged JPQL query
                // with SQL-side filters, so a non-final page is always exactly CSV_CHUNK rows
                // (external review, Copilot W3-1 #6 — verified, not a truncation bug).
                if (rows.size() < CSV_CHUNK) break;
            }
            writer.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"operations-log.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    private static String csvLine(AuditEntry entry) {
        return String.join(
                        ",",
                        Csv.cell(entry.getTs() != null ? entry.getTs().toString() : null),
                        Csv.cell(entry.getActor()),
                        Csv.cell(entry.getAction()),
                        Csv.cell(entry.getEngineId()),
                        Csv.cell(entry.getTenantId()),
                        Csv.cell(entry.getInstanceId()),
                        Csv.cell(entry.getOutcome() != null ? entry.getOutcome().name() : null),
                        Csv.cell(
                                entry.getHttpStatus() != null
                                        ? entry.getHttpStatus().toString()
                                        : null),
                        Csv.cell(entry.getReason()),
                        Csv.cell(entry.getTicketId()),
                        Csv.cell(entry.getCorrelationId()),
                        String.valueOf(entry.isBreakGlass()))
                + "\n";
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

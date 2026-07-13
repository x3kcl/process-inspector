package io.inspector.bulk;

import io.inspector.dto.SearchRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire shapes of the M5 bulk surface (SPEC §7) — grouped like the entity pair they mirror. */
public final class BulkDtos {

    private BulkDtos() {}

    /** One selected grid row: composite target (+ explicit dead-letter job for retry verbs). */
    public record BulkTarget(String engineId, String instanceId, String jobId) {}

    /**
     * The submit envelope. {@code continuedFrom} names the INTERRUPTED/partial job this
     * one re-scopes ("continue as new job", SPEC §7 — no automatic resume, ever).
     */
    public record BulkSubmitRequest(
            String verb, String reason, String ticketId, UUID continuedFrom, List<BulkTarget> items) {}

    /**
     * The triage-landing group retry (v1.x #1, SPEC §7): the browser sends the error-class
     * COORDINATES, never a member list — the BFF re-resolves the FAILED members server-side
     * from the same capped signature scan the triage cards are built on. {@code algoVersion}
     * pins the normalizer generation the card was rendered with (a hash from an older
     * algorithm must refuse, not silently match nothing). {@code engineId} narrows to one
     * engine's row on the card; null = every engine the group spans.
     */
    public record BulkErrorClassRequest(
            String signatureHash,
            Integer algoVersion,
            String processDefinitionKey,
            Integer definitionVersion,
            String engineId,
            String reason,
            String ticketId) {}

    /**
     * The select-all-matching-filter bulk (v1.x #2, SPEC §7): the browser sends the SEARCH
     * CRITERIA it is looking at, never a resolved ID list — server-side re-resolution is
     * binding. The BFF re-runs the M2a plan exhaustively at execution time, records the
     * resolved composite IDs in the envelope audit row, then dispatches. {@code reason} is
     * mandatory: the operator never enumerated these instances.
     */
    public record BulkFilterRequest(SearchRequest criteria, String verb, String reason, String ticketId) {}

    /**
     * Tier-4 destructive-bulk wizard (SPEC §6/§7, issue #100): the same "criteria are binding,
     * never a resolved ID list" doctrine as {@link BulkFilterRequest}, plus the operator's typed
     * scope attestation. {@code confirmedCount} is REQUIRED once the resolved scope touches a
     * PROD engine (mirrors the single-target typed-business-key rule, SPEC §6 tier 3) — checked
     * against a FRESH re-resolution at submit, never the preview's snapshot.
     */
    public record BulkDestructiveRequest(
            SearchRequest criteria, String verb, String reason, String ticketId, Integer confirmedCount) {}

    /**
     * The destructive-bulk wizard's scope-enumeration step (SPEC §6 tier 4: "count, per-engine
     * split, expandable list") — read-only, re-run server-fresh at submit, never trusted from an
     * earlier preview (same re-plan-server-fresh doctrine as migrate/change-state preview).
     * {@code sampleRows} is capped for display; {@code capped} says so honestly.
     */
    public record BulkDestructivePreview(
            long count,
            Map<String, Long> perEngineCounts,
            List<io.inspector.dto.ProcessInstanceRow> sampleRows,
            boolean capped,
            boolean prodInScope) {}

    public record BulkItemDto(
            int ordinal,
            String engineId,
            String instanceId,
            String jobRef,
            String state,
            String detail,
            UUID auditId,
            Instant finishedAt) {

        static BulkItemDto of(BulkJobItem item) {
            return new BulkItemDto(
                    item.getOrdinal(),
                    item.getEngineId(),
                    item.getInstanceId(),
                    item.getJobRef(),
                    item.getState().name(),
                    item.getDetail(),
                    item.getAuditId(),
                    item.getFinishedAt());
        }
    }

    /**
     * The job readout: {@code tallies} is the aggregate "N of M dispatched ·
     * ok/failed/skipped/unknown" line's data (R-SEM-11); {@code items} ships on the
     * detail read only. {@code scopeKind}/{@code scopeLabel} (usability fix E1) are the
     * scope-provenance descriptor threaded from whichever of the three submit doors
     * (ticked selection / error-class group / filter) produced the job.
     */
    public record BulkJobDto(
            UUID id,
            String verb,
            String state,
            String submittedBy,
            Instant submittedAt,
            Instant finishedAt,
            String reason,
            String ticketId,
            UUID continuedFrom,
            int totalItems,
            String scopeKind,
            String scopeLabel,
            Map<String, Long> tallies,
            List<BulkItemDto> items) {

        static BulkJobDto of(BulkJob job, List<BulkJobItem> items, boolean includeItems) {
            Map<String, Long> tallies = new java.util.LinkedHashMap<>();
            for (BulkJobItem item : items) {
                tallies.merge(item.getState().name(), 1L, Long::sum);
            }
            return new BulkJobDto(
                    job.getId(),
                    job.getVerb(),
                    job.getState().name(),
                    job.getSubmittedBy(),
                    job.getSubmittedAt(),
                    job.getFinishedAt(),
                    job.getReason(),
                    job.getTicketId(),
                    job.getContinuedFrom(),
                    job.getTotalItems(),
                    job.getScopeKind().name(),
                    job.getScopeLabel(),
                    tallies,
                    includeItems ? items.stream().map(BulkItemDto::of).toList() : null);
        }
    }
}

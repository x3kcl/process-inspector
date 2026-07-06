package io.inspector.bulk;

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
     * detail read only.
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
                    tallies,
                    includeItems ? items.stream().map(BulkItemDto::of).toList() : null);
        }
    }
}

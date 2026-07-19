package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/incidents/{id}} (R-BAU-10, INCIDENT-LEDGER.md §6): the list item shape
 * ({@link IncidentSummary}, embedded so list card and detail header share one contract) PLUS
 * the lifecycle history and the live Stage-0 join.
 *
 * <p>{@code episodes} is the complete per-episode history, newest first — the MTTR substrate:
 * {@code durationSeconds} is derived ({@code endedAt − startedAt}) whenever the episode has
 * ended, omitted while it is live. {@code series} is the occurrence time-series inside the
 * requested window (server-clamped like {@code /api/triage/trends}), ascending; a
 * {@code truncated} point is a FLOOR, not a dip (R-SEM-12) — {@code seriesWindow} echoes the
 * clamped window actually applied (ISO-8601 duration).
 *
 * <p>{@code live} is the CURRENT {@link ErrorGroup} for this incident's
 * {@code (signatureHash, algoVersion)} — joined at render time from the SAME shared cached
 * aggregation the triage dashboard serves (scope-projected + ack-decorated exactly like
 * {@code GET /api/triage}; never a second fan-out plan). Omitted when the class is not failing
 * right now, when its generation is retired, or when its live slice falls outside the caller's
 * scope.
 *
 * <p>{@code relatedBulkJobs} (S5) is the read-only remediation join: the most recent
 * error-class bulk retries whose submit envelope carried THIS incident's
 * {@code (signatureHash, algoVersion)} — the audit golden master is the join table (the
 * {@code bulk_job} row's scope descriptor is a human label, not the signature), the shape
 * mirrors the {@code GET /api/bulk} list item (id, verb, state, actor, timestamps, scope
 * descriptor, per-item tallies — item details stay on the bulk surface). Newest submit first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentDetail(
        IncidentSummary incident,
        List<Episode> episodes, // all, newest first
        String seriesWindow, // the CLAMPED window applied to the series, ISO-8601 (e.g. PT24H)
        List<OccurrencePoint> series, // ascending by sampledAt
        ErrorGroup live,
        List<RelatedBulkJob> relatedBulkJobs) { // newest submit first; [] when none

    /** One open→resolve cycle (INCIDENT-LEDGER.md §3.2); resolve metadata arrives with S3. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Episode(
            long id,
            String startState, // OPEN | REGRESSED — an episode never starts resolved
            Instant startedAt,
            Instant endedAt, // omitted while the episode is live
            String resolvedBy,
            String resolveReason,
            String ticketId,
            long peakTotal, // max observed live total this episode
            Long durationSeconds) {} // endedAt − startedAt; omitted while live

    /** One bucketed time-series point (INCIDENT-LEDGER.md §3.3) — the sparkline substrate. */
    public record OccurrencePoint(
            Instant sampledAt, long total, long deadLetterCount, long retryingCount, boolean truncated) {}

    /**
     * One related error-class bulk retry — the {@code GET /api/bulk} list item's exact field
     * set minus the per-item rows (those stay on the bulk surface, reachable by {@code id}).
     * {@code tallies} is the per-item outcome histogram ("N ok / M failed / …" — R-SEM-11's
     * honesty line); {@code scopeKind}/{@code scopeLabel} are the V4 scope descriptor.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelatedBulkJob(
            UUID id,
            String verb,
            String state,
            String submittedBy,
            Instant submittedAt,
            Instant finishedAt, // omitted while the job is live
            int totalItems,
            String scopeKind,
            String scopeLabel,
            Map<String, Long> tallies) {}
}

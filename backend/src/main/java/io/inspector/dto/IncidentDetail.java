package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentDetail(
        IncidentSummary incident,
        List<Episode> episodes, // all, newest first
        String seriesWindow, // the CLAMPED window applied to the series, ISO-8601 (e.g. PT24H)
        List<OccurrencePoint> series, // ascending by sampledAt
        ErrorGroup live) {

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
}

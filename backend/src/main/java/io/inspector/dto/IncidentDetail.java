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
 * {@code truncated} point is a FLOOR, not a dip (R-SEM-12) and a {@code cycleComplete = false}
 * point is BLIND — an engine was unreachable when it was written, so its dip is an outage rather
 * than a drain (#302/V21) — {@code seriesWindow} echoes the clamped window actually applied
 * (ISO-8601 duration).
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
 *
 * <p>Each {@link Episode} carries {@code actionsDuringEpisode} (issue #358 item 2): an honest,
 * audit-side aggregate of corrective actions attributed to that episode's window — see
 * {@link EpisodeActionAttribution}'s doc for the join mechanism and its accepted limitations.
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
            Long durationSeconds, // endedAt − startedAt; omitted while live
            EpisodeActionAttribution actionsDuringEpisode) {}

    /**
     * The episode-level corrective-action attribution join (issue #358 item 2,
     * RETRYING-RISK-LANE.md §3.2/§3.3/§10): every corrective-action audit row on an engine this
     * incident's (current) {@code countsByEngine} touches, whose timestamp falls inside this
     * episode's {@code [startedAt, endedAt-or-now]} window — {@code EpisodeActionAttributionService}.
     * Reuses the EXACT audit-side engine+timestamp join #351's self-heal confound detection
     * established (see that service's javadoc), widened from "successful retry-job in a
     * ±2-bucket tolerance" to "every verb/outcome inside the episode's own precise boundaries".
     *
     * <p><b>Honest limitations, same shape as #351's confound rule:</b> this is an
     * OVER-INCLUSIVE, class-agnostic aggregate, not an exact per-instance join — an action on the
     * incident's engine during the episode window counts even if it targeted a DIFFERENT failure
     * class active on that same engine at the same time (exact per-signature attribution would
     * need the triggering signature stamped onto the audit row at write time, which this slice
     * deliberately does NOT add — R-AUD-03 and the corrective-actions rails are untouched). It can
     * also UNDER-count at two edges: an action landing in the sub-{@code sampledAt}-beat instant
     * strictly before {@code startedAt} (the episode's own first-observation timestamp, not a
     * bucket boundary) is excluded even if it caused the sighting, and an action taken while the
     * incident sat RESOLVED — after one episode's {@code endedAt} and before a later regression's
     * {@code startedAt} — falls into neither episode's window and is invisible to this join
     * entirely (see RETRYING-RISK-LANE.md §3.3 for the full discussion).
     * {@code byVerb}/{@code byOutcome} are aggregate TALLIES only — no engine id, instance id,
     * actor, or reason ever leaves this join. The engine set feeding the scan is the incident's
     * CURRENT (raw, unscoped) snapshot (same precedent as the self-heal confound scan): a class's
     * historic per-episode engine set is not reconstructable from the ledger, so a registry change
     * can shift which engines a HISTORIC episode's tally draws from — and BECAUSE the scan is
     * unscoped, this whole field is OMITTED, never narrowed, whenever the incident's OWN
     * projection is partially scoped (R-SAFE-17): a fail-closed choice mirroring #329's
     * {@code relatedBulkJobs} narrowing on this same endpoint, so a partially-scoped VIEWER can
     * never learn about action activity on an engine outside their own read scope through this
     * field. {@code truncated} is honest per the per-episode scan cap: never present a capped
     * tally as complete.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EpisodeActionAttribution(
            int count, // total attributed actions this episode (capped — see truncated)
            Map<String, Long> byVerb, // action path (e.g. "retry-job") -> count
            Map<String, Long> byOutcome, // ok | failed | unknown | PENDING -> count
            boolean truncated) {}

    /**
     * One bucketed time-series point (INCIDENT-LEDGER.md §3.3) — the sparkline substrate, with
     * BOTH of the row's honesty markers (V21). {@code truncated} = the failure-lane scan hit its
     * cap on that sample, so the count is a FLOOR, not a level (R-SEM-12). {@code cycleComplete}
     * = every registry engine actually answered on the pass that wrote the row (#302); when it is
     * false the sample is missing an unreachable engine's members, so the dip the chart draws is
     * an OUTAGE, not a drain.
     *
     * <p>Shipping {@code cycleComplete} is the same iron rule that put {@code truncated} here —
     * never render a status derived from truncated data without the badge. Without it the detail
     * sparkline drew a blind-outage dip identically to a real recovery, which is precisely the
     * confusion the marker was persisted to prevent.
     *
     * <p>{@code fleet} (V22, #372) is the row's observation SCOPE, not a third quality marker: the
     * canonical sorted comma-joined ids of the ENABLED engines the writing pass fanned out over
     * ({@code ""} = scope unrecorded). Two points are difference-comparable only when both carry
     * the SAME non-empty fleet — a registry disable/enable moves the level of a multi-engine class
     * without either quality marker noticing. It ships so the VIEWER/REST-only measurement method
     * (ALARM-COST-MODEL §16.7's era-boundary walk, G5) can see era boundaries inside the window it
     * reaches. Frontend RENDERING of era boundaries is a named non-goal of this slice.
     */
    public record OccurrencePoint(
            Instant sampledAt,
            long total,
            long deadLetterCount,
            long retryingCount,
            boolean truncated,
            boolean cycleComplete,
            String fleet) {}

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

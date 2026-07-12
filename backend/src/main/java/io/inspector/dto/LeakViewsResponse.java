package io.inspector.dto;

import java.util.List;
import java.util.Map;

/**
 * GET /api/triage/leak-views — the Stage 0 "Leak views" panel (SPEC §4, R-BAU-02): the slow
 * leaks that never enter a failure lane, grouped per definition ("vacationRequest: 212 > 30d").
 *
 * <p>Aggregation-independent by the same doctrine as the triage dashboard: every count is a
 * count-only ({@code size=1}) runtime query, NEVER the grid-search plan (iron rule). Age is
 * {@code now − startTime} via the {@code startedBefore} predicate for all three windows —
 * including the SUSPENDED one, because Flowable records no suspension timestamp (R-SEM-05): the
 * "Suspended · started > 7 days ago" window is honestly "currently suspended AND started > 7d
 * ago", and the frontend label says exactly that.
 *
 * <p>{@code windows} carries the EXACT {@code startedBefore} instant used for each count so the
 * frontend's chip deep-links replay the identical predicate the count was measured against.
 * {@code lowerBound} is true (and {@code unavailableEngines} names them) whenever an engine was
 * unreachable or its definition list was truncated — every count then carries the standard
 * lower-bound badge, matching the grid (R-SEM-12).
 */
public record LeakViewsResponse(
        String asOf,
        LeakWindows windows,
        List<LeakDefinitionCount> definitions,
        boolean lowerBound,
        List<String> unavailableEngines) {

    /** The exact {@code startedBefore} ISO-8601 UTC boundary used per window (age = now − it). */
    public record LeakWindows(String activeOver30d, String activeOver90d, String suspendedStartedOver7d) {}

    /**
     * One definition's leak counts, merged across every reachable engine that deploys the key.
     * A definition appears only when at least one window count is non-zero.
     *
     * <p>{@code countsByEngine} (issue #126, S2 R-SAFE-17) is the per-engine breakdown that lets
     * {@code LeakViewScopeProjector} post-cache render-time scope this fleet-wide aggregate
     * exactly like {@code TriageScopeProjector} does the dashboard: unlike {@code ErrorGroup}'s
     * dead-letter/retrying split, EVERY window count here decomposes per engine, so a scoped
     * caller's totals are honestly recomputed from survivors — never nulled. {@code partial} is
     * true when the key ALSO runs on at least one engine outside the caller's scope, so the UI
     * can badge "this total may not cover every engine this definition runs on" without
     * misrepresenting the (fully honest) numbers themselves. Both are {@code false}/empty-by-fleet
     * on the unscoped aggregation; a projected response never has {@code partial=true} paired with
     * an engine set equal to {@code countsByEngine}'s own keys (that would be a contradiction).
     */
    public record LeakDefinitionCount(
            String definitionKey,
            long activeOver30d,
            long activeOver90d,
            long suspendedStartedOver7d,
            Map<String, EngineLeakCount> countsByEngine,
            boolean partial) {

        /** One engine's contribution to a definition's leak counts. */
        public record EngineLeakCount(long activeOver30d, long activeOver90d, long suspendedStartedOver7d) {}
    }
}

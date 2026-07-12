package io.inspector.api;

import io.inspector.dto.LeakViewsResponse;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageTrendResponse;
import io.inspector.triage.ErrorGroupAckService;
import io.inspector.triage.LeakViewScopeProjector;
import io.inspector.triage.LeakViewService;
import io.inspector.triage.TriageScopeProjector;
import io.inspector.triage.TriageService;
import io.inspector.triage.TriageTrendService;
import java.time.Duration;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/triage — the Stage 0 landing (SPEC §4): engine health strip, global status
 * counts and error groups, served from the 20s BFF cache. {@code ?refresh=true} bypasses
 * the cache (rate-limited in {@link TriageService}; a throttled refresh serves the
 * cached snapshot — the {@code asOf} stamp tells the truth either way). Always a 200:
 * engine failures degrade into the response's {@code perEngine} envelopes.
 *
 * GET /api/triage/trends — the R-BAU-08 job-lane history for the Stage-0 sparklines, read
 * from the v2/M4 snapshot store (never the live engine). Same openness as the dashboard:
 * it trends the exact counts the (already-open) landing shows.
 */
@RestController
@RequestMapping("/api/triage")
public class TriageController {

    /** Clamp the look-back so a crafted {@code hours} can never scan an unbounded window. */
    private static final int MAX_TREND_HOURS = 24 * 30;

    private final TriageService triage;
    private final TriageTrendService trends;
    private final ErrorGroupAckService acks;
    private final LeakViewService leakViews;
    private final TriageScopeProjector scopeProjector;
    private final LeakViewScopeProjector leakViewScopeProjector;

    public TriageController(
            TriageService triage,
            TriageTrendService trends,
            ErrorGroupAckService acks,
            LeakViewService leakViews,
            TriageScopeProjector scopeProjector,
            LeakViewScopeProjector leakViewScopeProjector) {
        this.triage = triage;
        this.trends = trends;
        this.acks = acks;
        this.leakViews = leakViews;
        this.scopeProjector = scopeProjector;
        this.leakViewScopeProjector = leakViewScopeProjector;
    }

    @GetMapping
    public TriageDashboardResponse dashboard(
            @RequestParam(defaultValue = "false") boolean refresh, Authentication auth) {
        // S2 (R-SAFE-17): scope the SHARED cached aggregation to the caller's readable engines as a
        // per-request render-time projection (never a scope-aware cache key, never inside the
        // aggregation the background sampler drives). Project first, THEN join ack state — decorate
        // keys on signatureHash, which the projection preserves, so it operates on the scoped groups.
        // R-BAU-01: ack state is live on every read, never cached with (or busting) the engine data.
        return acks.decorate(scopeProjector.project(triage.dashboard(refresh), auth));
    }

    @GetMapping("/trends")
    public TriageTrendResponse trends(@RequestParam(defaultValue = "24") int hours, Authentication auth) {
        int bounded = Math.max(1, Math.min(hours, MAX_TREND_HOURS));
        // S2 (R-SAFE-17): scoped inline (no shared cache to protect here — see TriageTrendService).
        return trends.trends(Duration.ofHours(bounded), auth);
    }

    /**
     * GET /api/triage/leak-views — the R-BAU-02 "Leak views" panel: long-running and
     * long-suspended instances grouped per definition, from count-only Stage-0 queries. Age is
     * {@code now − startTime} for every window (R-SEM-05: the SUSPENDED window too — there is no
     * suspension timestamp). Cached like the dashboard; always a 200 (a down engine degrades to
     * a named lower bound, never a failed response).
     */
    @GetMapping("/leak-views")
    public LeakViewsResponse leakViews(Authentication auth) {
        // S2 (R-SAFE-17): scope the SHARED cached aggregation post-cache, same doctrine as dashboard().
        return leakViewScopeProjector.project(leakViews.leakViews(), auth);
    }
}

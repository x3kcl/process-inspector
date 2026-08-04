package io.inspector.attention;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The C3 estimator for the R-BAU-01 auto-resurface threshold (ALARM-COST-MODEL.md §3.3, #353) —
 * pure math, no stores, no Spring.
 *
 * <p>Today's +20 % is an admitted intuition constant. The model-derived replacement must simply
 * exceed normal within-episode count JITTER, so a resurface fires on genuine growth rather than on
 * noise: {@code t(c) = max(floor_pct, k · CV(c))}, with {@code k} fit to a false-resurface budget.
 *
 * <p><b>Why counterfactual (the adopted panel BLOCKER fix).</b> The original estimator was
 * undefined at zero real acks — and the pilot has recorded exactly zero acks, ever (§5.3), so
 * "false resurfaces per ack-day" had no data to be computed from. Fitting {@code k} therefore
 * SIMULATES an ack at every stable occurrence-series point and replays the threshold forward:
 * every input comes from {@code incident_occurrence} alone, so the estimator is well-defined on a
 * ledger where nobody has ever acknowledged anything. Real ack lifecycles (gate G4) are still
 * required to VALIDATE the fitted threshold before it takes effect — which is why the derived
 * value is behind {@code inspector.triage.attention.derived-resurface-threshold}, default false,
 * with the 20 % constant in force until the full §7 gate passes.
 *
 * <p><b>"Stable segment"</b> is operationalized as a maximal run of consecutive non-truncated,
 * non-zero buckets: a zero bucket is the class draining (an episode boundary in all but name) and
 * a truncated bucket is a FLOOR, not a level (R-SEM-12), so neither may be differenced across.
 * Jitter is measured WITHIN segments and acks are simulated only inside them.
 *
 * <p><b>"False"</b> is a resurface whose growth does not hold: the total falls back to at or below
 * the acknowledged baseline within the settle window. That is jitter by definition — the operator
 * was interrupted for nothing. Growth that holds is genuine and the resurface was correct.
 */
public final class CounterfactualAckReplay {

    /**
     * How long growth must hold to count as genuine, in buckets (~15 min at the 60 s sampler
     * beat). A replay-resolution choice, not a product knob: it exists because a resurface is
     * judged by what the series does NEXT, and "next" needs a horizon.
     */
    static final int SETTLE_BUCKETS = 15;

    /** The {@code k} grid searched by {@link #fitK}; the smallest qualifying value wins. */
    private static final double K_STEP = 0.5;

    private static final double K_MAX = 10.0;

    private CounterfactualAckReplay() {}

    /** One occurrence row reduced to what the estimator reads. */
    public record SeriesPoint(long total, boolean truncated) {}

    /**
     * The replay's verdict over a whole class. {@code resurfaces} counts every JUDGED resurface
     * (false ones included) and exists so a caller can tell "the threshold met the budget" apart
     * from "the threshold was never exercised" — the review's confirmed FIX 4: on a zero-jitter
     * class no candidate ever fires, so {@code falsePer30AckDays = 0 ≤ budget} holds VACUOUSLY
     * and the smallest grid {@code k} wins on no evidence at all. A fit is only a fit when the
     * data actually exercised it.
     */
    public record ReplayOutcome(int resurfaces, int falseResurfaces, double ackDays, double falsePer30AckDays) {}

    /** Maximal runs of consecutive non-truncated, non-zero buckets (see class doc). */
    public static List<List<Long>> stableSegments(List<SeriesPoint> series) {
        List<List<Long>> segments = new ArrayList<>();
        List<Long> current = new ArrayList<>();
        for (SeriesPoint point : series) {
            if (point.truncated() || point.total() <= 0) {
                if (current.size() > 1) {
                    segments.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                continue;
            }
            current.add(point.total());
        }
        if (current.size() > 1) {
            segments.add(List.copyOf(current));
        }
        return segments;
    }

    /**
     * Within-segment count jitter as a coefficient of variation — the MEDIAN of the per-segment
     * CVs, so one long noisy stretch cannot define the class. {@code 0} when there is nothing to
     * measure (which is the measured pilot state: CV ≈ 0 on both live classes, §5.6 — a
     * near-static demo artifact, and one more argument for the §7 gate).
     */
    public static double coefficientOfVariation(List<List<Long>> segments) {
        List<Long> scaled = new ArrayList<>();
        for (List<Long> segment : segments) {
            double mean = segment.stream().mapToLong(Long::longValue).average().orElse(0);
            if (mean <= 0) {
                continue;
            }
            double variance = segment.stream()
                            .mapToDouble(value -> (value - mean) * (value - mean))
                            .sum()
                    / segment.size();
            // Carried as parts-per-million so the shared nearest-rank median can stay integral.
            scaled.add(Math.round(Math.sqrt(variance) / mean * 1_000_000d));
        }
        Long median = Quantiles.median(scaled);
        return median == null ? 0d : median / 1_000_000d;
    }

    /**
     * Simulates an ack at every point of every stable segment, replays {@code thresholdPct}
     * forward, and counts the resurfaces that turned out to be jitter. {@code ackDays} is the
     * simulated mute time accrued across all those acks — the denominator the §3.3 budget is
     * expressed in.
     */
    public static ReplayOutcome replay(List<List<Long>> segments, Duration bucketWidth, double thresholdPct) {
        double bucketDays = Math.max(1, bucketWidth.toSeconds()) / 86_400d;
        int resurfaces = 0;
        int falseResurfaces = 0;
        double ackDays = 0;
        for (List<Long> segment : segments) {
            for (int ack = 0; ack < segment.size() - 1; ack++) {
                long baseline = segment.get(ack);
                if (baseline <= 0) {
                    continue;
                }
                double trigger = baseline * (1 + thresholdPct / 100d);
                int resurface = -1;
                for (int i = ack + 1; i < segment.size(); i++) {
                    if (segment.get(i) > trigger) {
                        resurface = i;
                        break;
                    }
                }
                if (resurface < 0) {
                    ackDays += (segment.size() - 1 - ack) * bucketDays; // muted to the end, correctly
                    continue;
                }
                if (censored(segment, resurface)) {
                    continue; // unobservable outcome — drop the SAMPLE, both numerator and denominator
                }
                resurfaces++;
                ackDays += (resurface - ack) * bucketDays;
                if (!growthHeld(segment, resurface, baseline)) {
                    falseResurfaces++;
                }
            }
        }
        double per30 = ackDays > 0 ? falseResurfaces / ackDays * 30d : 0d;
        return new ReplayOutcome(resurfaces, falseResurfaces, ackDays, per30);
    }

    /**
     * True when the settle window would run past the end of the segment, so whether the growth
     * held is simply NOT OBSERVABLE (review fix, FIX 5).
     *
     * <p>As first shipped {@link #growthHeld} silently SHORTENED the window
     * ({@code end = min(size-1, resurface + SETTLE_BUCKETS)}) instead of discarding the censored
     * sample. When the resurface landed on the LAST index the loop then ran exactly once, at an
     * index which by construction holds a value above the trigger and therefore above the
     * baseline — so it ALWAYS returned true. And {@link #stableSegments} breaks at every zero and
     * every truncated bucket, so the very common "class spikes, then drains to zero" shape puts
     * the spike at the segment TAIL: exactly the case that should have counted as a false
     * resurface was instead recorded as proven growth. The bias ran one way — false resurfaces
     * under-counted ⇒ fitted {@code k} too small ⇒ a shipped threshold delivering MORE false
     * resurfaces than the budget promises. Censoring is not evidence of success.
     */
    private static boolean censored(List<Long> segment, int resurface) {
        return resurface + SETTLE_BUCKETS > segment.size() - 1;
    }

    /** Genuine growth stays strictly above the acknowledged baseline for the WHOLE settle window. */
    private static boolean growthHeld(List<Long> segment, int resurface, long baseline) {
        for (int i = resurface; i <= resurface + SETTLE_BUCKETS; i++) {
            if (segment.get(i) <= baseline) {
                return false;
            }
        }
        return true;
    }

    /**
     * The smallest {@code k} on the grid whose replayed threshold meets the false-resurface budget
     * (§3.3 target: ≤ 1 per 30 ack-days), or EMPTY when the series cannot fit one at all.
     * Smallest wins because an over-large {@code k} buys quiet by never resurfacing at all — the
     * failure mode that turns an ack into a permanent mute, which R-BAU-01 exists to prevent.
     *
     * <p><b>Vacuous fits are rejected (review fix, FIX 4).</b> A budget met because NOTHING EVER
     * FIRED is not a fit — the series never exercised any candidate on the grid, so it carries no
     * information about the threshold at all. That is not a hypothetical: it is the MEASURED
     * pilot state (§5.6, "CV ≈ 0 on both live classes"). With {@code CV = 0} every grid candidate
     * collapses to {@code max(floorPct, 0) = floorPct}, nothing ever crosses it, and the very
     * first {@code k = 0.5} "won" on that emptiness — so opting in moved every static class from
     * a 20 % resurface threshold to a 10 % one (twice the ack interruptions) precisely because
     * there was no data. The class is now reported UNFITTABLE and the caller keeps its constant.
     *
     * <p>The probe is the SMALLEST candidate only, and that is exactly right rather than merely
     * cheap: {@code candidate(k)} is non-decreasing in {@code k} and the resurface count is
     * non-increasing in the candidate, so "the smallest candidate fires nothing" is equivalent to
     * "no candidate on the grid fires anything". A genuinely NOISY class is unaffected — its
     * small candidates fire plenty, and the winning larger candidate suppressing them is the
     * jitter guard §3.3 is asking for, not an absence of evidence.
     */
    public static OptionalDouble fitK(
            List<List<Long>> segments, Duration bucketWidth, int floorPct, double budgetPer30AckDays) {
        double cv = coefficientOfVariation(segments);
        if (replay(segments, bucketWidth, Math.max(floorPct, K_STEP * cv * 100d))
                        .resurfaces()
                == 0) {
            return OptionalDouble.empty();
        }
        for (double k = K_STEP; k <= K_MAX; k += K_STEP) {
            double candidate = Math.max(floorPct, k * cv * 100d);
            if (replay(segments, bucketWidth, candidate).falsePer30AckDays() <= budgetPer30AckDays) {
                return OptionalDouble.of(k);
            }
        }
        return OptionalDouble.of(K_MAX);
    }

    /**
     * The derived threshold percentage for one class — {@code max(floor_pct, k · CV(c))}, rounded
     * UP so rounding never lowers the jitter guard below the fit. EMPTY when the class's series
     * cannot fit a {@code k} (no segments, or a replay that never fired); the caller then keeps
     * its own constant, because a threshold nobody could derive is not a derived threshold.
     */
    public static OptionalInt thresholdPct(
            List<SeriesPoint> series, Duration bucketWidth, int floorPct, double budgetPer30AckDays) {
        List<List<Long>> segments = stableSegments(series);
        if (segments.isEmpty()) {
            return OptionalInt.empty();
        }
        OptionalDouble k = fitK(segments, bucketWidth, floorPct, budgetPer30AckDays);
        if (k.isEmpty()) {
            return OptionalInt.empty();
        }
        double derived = k.getAsDouble() * coefficientOfVariation(segments) * 100d;
        return OptionalInt.of((int) Math.max(floorPct, Math.ceil(derived)));
    }
}

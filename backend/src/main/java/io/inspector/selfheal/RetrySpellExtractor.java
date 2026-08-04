package io.inspector.selfheal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure extraction of RETRYING spells from one class's chronological occurrence series
 * (RETRYING-RISK-LANE.md §3.1) — zero Spring, zero JPA: a rung-1-testable transform,
 * {@code List<SpellSample> -> List<RetrySpell>}, over synthetic fixtures (no real self-heal
 * data exists yet — docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md).
 *
 * <p>A spell is a maximal run of samples with {@code retryingCount > 0}, ended by a sample
 * with {@code retryingCount = 0} (or left {@code live} when the series ends mid-run — live
 * spells never contribute to the statistic, §4.2 rule 1: completed-evidence-only). Outcome is
 * judged over the spell PLUS one bucket after its end (the design's outcome look-ahead): the
 * dead-letter count at the {@code +1} bucket sample versus the spell's FIRST sample — a
 * standing non-zero DLQ at spell start does NOT disqualify (the delta is the evidence, not a
 * clean floor). When that look-ahead sample is missing or too far away to trust, the spell
 * cannot be honestly judged and is treated as gap-voided rather than guessed.
 *
 * <p><b>A blind sample is not evidence (#302, V21).</b> This transform is an EDGE DETECTOR on
 * {@code retryingCount}, so an unobserved engine forges edges: a class with RETRYING jobs on
 * engine B and dead-letters on engine A stays present (A's members keep {@code total > 0}) while
 * B is unreachable, and the row written that pass carries {@code retryingCount = 0} — the spell
 * appears to END. The look-ahead then compares A's UNCHANGED dead-letter count against spell
 * start, finds no growth, and records {@code SELF_HEALED}: an engine outage entered as evidence
 * of autonomous healing, counted into {@code n}, the Wilson bound, the displayed lane and (via
 * the R1 {@code S} factor) a 4x rank demotion on a class that never healed. {@code
 * truncationTainted} does not cover it (that is the scan cap) and {@link #hasInternalGap} never
 * fires (the rows exist). So any spell CONTAINING, TERMINATED BY, or JUDGED AGAINST a
 * {@code cycleComplete = false} sample is gap-voided outright — unobservable shape, never a
 * guess (§5: never presents as complete).
 */
public final class RetrySpellExtractor {

    /** §3.1: a gap of MORE than this many buckets inside a spell voids its observed shape. */
    static final int GAP_VOID_BUCKETS = 5;

    /** §3.3: the confound tolerance either side of a spell boundary, in sampler buckets. */
    static final int CONFOUND_BUCKETS = 2;

    private RetrySpellExtractor() {}

    public static List<RetrySpell> extract(
            List<SpellSample> chronological, Duration bucketWidth, List<ConfoundWindow> confoundWindows) {
        List<RetrySpell> spells = new ArrayList<>();
        int n = chronological.size();
        int i = 0;
        while (i < n) {
            if (chronological.get(i).retryingCount() <= 0) {
                i++;
                continue;
            }
            int start = i;
            int end = i; // last index with retryingCount > 0
            while (end + 1 < n && chronological.get(end + 1).retryingCount() > 0) {
                end++;
            }
            int zeroIdx = end + 1; // the ending zero-count sample, if the series has one
            boolean live = zeroIdx >= n; // no closing zero observed — the series ends mid-spell
            spells.add(buildSpell(chronological, start, end, zeroIdx, live, bucketWidth, confoundWindows));
            i = live ? n : zeroIdx + 1;
        }
        return spells;
    }

    private static RetrySpell buildSpell(
            List<SpellSample> samples,
            int start,
            int end,
            int zeroIdx,
            boolean live,
            Duration bucketWidth,
            List<ConfoundWindow> confoundWindows) {
        Instant startAt = samples.get(start).sampledAt();
        Instant endAt = samples.get(end).sampledAt();
        Duration duration = Duration.between(startAt, endAt);

        boolean truncationTainted = false;
        for (int k = start; k <= end; k++) {
            if (samples.get(k).truncated()) {
                truncationTainted = true;
                break;
            }
        }

        int shapeEnd = Math.min(zeroIdx, samples.size() - 1);
        boolean gapVoided = hasInternalGap(samples, start, shapeEnd, bucketWidth);
        // A blind pass anywhere in the observed shape — inside the run OR the zero-count sample
        // that appears to END it — makes every edge here a possible artifact of an unreachable
        // engine rather than of the jobs. Same lane as a sampling gap: unobservable, and (unlike
        // a sampling gap, whose surviving endpoints are at least real counts) not even judged —
        // the outcome test itself would be reading a count with members missing from it.
        boolean blindShape = hasBlindSample(samples, start, shapeEnd);
        gapVoided = gapVoided || blindShape;

        RetrySpell.Outcome outcome = RetrySpell.Outcome.UNKNOWN;
        if (!live && !blindShape) {
            int lookaheadIdx = zeroIdx + 1; // the +1-bucket look-ahead past spell end
            if (lookaheadIdx < samples.size()
                    && withinLookaheadTolerance(
                            samples.get(zeroIdx).sampledAt(),
                            samples.get(lookaheadIdx).sampledAt(),
                            bucketWidth)) {
                if (samples.get(lookaheadIdx).cycleComplete()) {
                    long dlqAtStart = samples.get(start).deadLetterCount();
                    long dlqAfterLookahead = samples.get(lookaheadIdx).deadLetterCount();
                    outcome = dlqAfterLookahead > dlqAtStart
                            ? RetrySpell.Outcome.ESCALATED
                            : RetrySpell.Outcome.SELF_HEALED;
                } else {
                    // The whole outcome test is "did the DLQ grow by the look-ahead bucket" — a
                    // blind look-ahead may simply be missing the engine the escalation landed on,
                    // so "no growth" there is not evidence of a heal.
                    gapVoided = true;
                }
            } else {
                // No usable look-ahead: the spell's outcome cannot be honestly judged — an
                // unobservable shape, never a guess (§5: never presents as complete).
                gapVoided = true;
            }
        }

        boolean confounded = !live && confoundWindows.stream().anyMatch(w -> w.overlaps(startAt, endAt));
        // Already in progress at the very first sample we have (window start, or the row cap):
        // dlqAtStart is a MID-spell level and the duration is short by the unobserved prefix.
        boolean leftCensored = start == 0;

        return new RetrySpell(
                startAt, endAt, duration, outcome, confounded, gapVoided, truncationTainted, live, leftCensored);
    }

    /** True when any sample in {@code [start, endInclusive]} was written by a blind pass (#302). */
    private static boolean hasBlindSample(List<SpellSample> samples, int start, int endInclusive) {
        for (int k = start; k <= endInclusive; k++) {
            if (!samples.get(k).cycleComplete()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInternalGap(
            List<SpellSample> samples, int start, int endInclusive, Duration bucketWidth) {
        long maxGapMillis = (long) GAP_VOID_BUCKETS * bucketWidth.toMillis();
        for (int k = start; k < endInclusive; k++) {
            long gapMillis = Duration.between(
                            samples.get(k).sampledAt(), samples.get(k + 1).sampledAt())
                    .toMillis();
            if (gapMillis > maxGapMillis) {
                return true;
            }
        }
        return false;
    }

    /**
     * A tighter check than {@link #GAP_VOID_BUCKETS}: is the sample right after the ending
     * zero-count sample close enough to actually BE the nominal +1-bucket look-ahead (scheduler
     * jitter tolerated up to 2 buckets), rather than the next surviving sample after a real gap.
     */
    private static boolean withinLookaheadTolerance(Instant zeroAt, Instant lookaheadAt, Duration bucketWidth) {
        long toleranceMillis = bucketWidth.toMillis() * CONFOUND_BUCKETS;
        return Duration.between(zeroAt, lookaheadAt).toMillis() <= toleranceMillis;
    }
}

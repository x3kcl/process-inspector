package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.inspector.attention.CounterfactualAckReplay.SeriesPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the C3 resurface-threshold estimator (ALARM-COST-MODEL.md §3.3) against synthetic
 * occurrence series.
 *
 * <p>The adopted panel BLOCKER was that the original estimator was undefined at zero real acks —
 * and the pilot has recorded zero acks, ever. So the property that actually matters here is that
 * every one of these tests fits a threshold from an occurrence series ALONE, with no ack in any
 * fixture.
 */
class CounterfactualAckReplayTest {

    private static final Duration BUCKET = Duration.ofSeconds(60);
    private static final int FLOOR_PCT = 10;
    private static final double BUDGET = 1.0; // ≤ 1 false resurface per 30 ack-days

    /* ---------------- stable segments ---------------- */

    @Test
    void aZeroBucketEndsASegmentBecauseTheClassDrained() {
        List<SeriesPoint> series = points(new long[] {5, 6, 5, 0, 0, 7, 8, 7}, false);

        assertThat(CounterfactualAckReplay.stableSegments(series))
                .containsExactly(List.of(5L, 6L, 5L), List.of(7L, 8L, 7L));
    }

    @Test
    void aTruncatedBucketEndsASegmentBecauseAFloorIsNotALevel() {
        // R-SEM-12: differencing across a truncated point manufactures phantom jitter.
        List<SeriesPoint> series = new ArrayList<>();
        series.add(new SeriesPoint(5, false));
        series.add(new SeriesPoint(6, false));
        series.add(new SeriesPoint(400, true));
        series.add(new SeriesPoint(7, false));
        series.add(new SeriesPoint(8, false));

        assertThat(CounterfactualAckReplay.stableSegments(series)).containsExactly(List.of(5L, 6L), List.of(7L, 8L));
    }

    @Test
    void aLoneBucketIsNotASegmentBecauseThereIsNothingToReplayForward() {
        assertThat(CounterfactualAckReplay.stableSegments(points(new long[] {9, 0, 4, 0, 9}, false)))
                .isEmpty();
    }

    /* ---------------- jitter ---------------- */

    @Test
    void aPerfectlyFlatClassHasZeroJitterWhichIsExactlyTheMeasuredPilotState() {
        // §5.6: CV ≈ 0 on both live pilot classes — a near-static demo artifact, flagged as such.
        List<List<Long>> flat = CounterfactualAckReplay.stableSegments(points(new long[] {21, 21, 21, 21}, false));

        assertThat(CounterfactualAckReplay.coefficientOfVariation(flat)).isEqualTo(0d);
    }

    @Test
    void jitterIsTheStandardDeviationOverTheMeanWithinASegment() {
        List<List<Long>> segments = List.of(List.of(8L, 12L)); // mean 10, population sd 2

        assertThat(CounterfactualAckReplay.coefficientOfVariation(segments)).isCloseTo(0.2, within(1e-6));
    }

    @Test
    void theMedianAcrossSegmentsMeansOneNoisyStretchCannotDefineTheClass() {
        List<List<Long>> segments = List.of(List.of(10L, 10L), List.of(8L, 12L), List.of(1L, 99L));

        assertThat(CounterfactualAckReplay.coefficientOfVariation(segments)).isCloseTo(0.2, within(1e-6));
    }

    /* ---------------- the replay ---------------- */

    @Test
    void aTransientSpikeThatFallsStraightBackIsCountedAsAFalseResurface() {
        // Ack at 100, one bucket blips to 130, then settles back to 100 — pure jitter.
        List<List<Long>> segments = List.of(sustain(100, 5, 130, 1, 100, 40));

        var outcome = CounterfactualAckReplay.replay(segments, BUCKET, 20);

        assertThat(outcome.falseResurfaces()).isPositive();
        assertThat(outcome.ackDays()).isPositive();
    }

    @Test
    void growthThatHoldsThroughTheSettleWindowIsAGenuineResurfaceNotAFalseOne() {
        List<List<Long>> segments = List.of(sustain(100, 5, 130, 40, 130, 0));

        assertThat(CounterfactualAckReplay.replay(segments, BUCKET, 20).falseResurfaces())
                .isZero();
    }

    @Test
    void aThresholdNothingEverCrossesFiresNoResurfacesAtAll() {
        List<List<Long>> segments = List.of(sustain(100, 30, 105, 30, 100, 30));

        assertThat(CounterfactualAckReplay.replay(segments, BUCKET, 50).falseResurfaces())
                .isZero();
    }

    /* ---------------- the fit ---------------- */

    @Test
    void theSmallestQualifyingKWinsSoAnAckNeverBecomesAPermanentMute() {
        List<List<Long>> quiet = List.of(sustain(100, 60, 100, 60, 100, 60));

        // Nothing ever crosses even the floor here, so the very first grid point qualifies.
        assertThat(CounterfactualAckReplay.fitK(quiet, BUCKET, FLOOR_PCT, BUDGET))
                .isEqualTo(0.5);
    }

    @Test
    void theDerivedThresholdNeverDropsBelowTheFloorEvenAtZeroJitter() {
        List<SeriesPoint> flat = points(new long[] {21, 21, 21, 21, 21, 21}, false);

        assertThat(CounterfactualAckReplay.thresholdPct(flat, BUCKET, FLOOR_PCT, BUDGET))
                .isEqualTo(FLOOR_PCT);
    }

    @Test
    void aNoisyClassEarnsAThresholdAboveTheFloor() {
        long[] noisy = new long[80];
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] = i % 2 == 0 ? 60 : 140; // CV ≈ 0.4 — heavy, sustained jitter
        }

        int derived = CounterfactualAckReplay.thresholdPct(points(noisy, false), BUCKET, FLOOR_PCT, BUDGET);

        assertThat(derived).isGreaterThan(FLOOR_PCT);
    }

    @Test
    void aSeriesWithNothingToSegmentFallsBackToTheFloorRatherThanInventingAThreshold() {
        assertThat(CounterfactualAckReplay.thresholdPct(List.of(), BUCKET, FLOOR_PCT, BUDGET))
                .isEqualTo(FLOOR_PCT);
    }

    @Test
    void everyFixtureInThisClassIsFitWithoutASingleAcknowledgementExisting() {
        // The panel BLOCKER, as an executable claim: SeriesPoint carries occurrence data only.
        assertThat(SeriesPoint.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("total", "truncated");
    }

    /* ---------------- fixtures ---------------- */

    private static List<SeriesPoint> points(long[] totals, boolean truncated) {
        List<SeriesPoint> series = new ArrayList<>(totals.length);
        for (long total : totals) {
            series.add(new SeriesPoint(total, truncated));
        }
        return series;
    }

    /** {@code a} for {@code na} buckets, then {@code b} for {@code nb}, then {@code c} for {@code nc}. */
    private static List<Long> sustain(long a, int na, long b, int nb, long c, int nc) {
        List<Long> segment = new ArrayList<>(na + nb + nc);
        for (int i = 0; i < na; i++) {
            segment.add(a);
        }
        for (int i = 0; i < nb; i++) {
            segment.add(b);
        }
        for (int i = 0; i < nc; i++) {
            segment.add(c);
        }
        return segment;
    }
}

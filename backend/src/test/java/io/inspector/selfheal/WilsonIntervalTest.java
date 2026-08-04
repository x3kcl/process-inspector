package io.inspector.selfheal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Rung 1: the 95% Wilson score interval, pinned against the MEASURED sample-size-floor
 * arithmetic table in docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md (real numbers computed
 * against the pilot ledger's own historic accounting — reproduced here as fixed-point
 * fixtures, no live data needed).
 */
class WilsonIntervalTest {

    private static final org.assertj.core.data.Offset<Double> TOLERANCE = within(0.001);

    @Test
    void perfectRecordAtTheChosenFloorOfTenClearsTheEnterThreshold() {
        WilsonInterval.Bounds bounds = WilsonInterval.score(10, 10);

        assertThat(bounds.low()).isEqualTo(0.722, TOLERANCE);
    }

    @Test
    void perfectRecordAtNineSitsExactlyOnTheEnterThresholdKnifeEdge() {
        WilsonInterval.Bounds bounds = WilsonInterval.score(9, 9);

        assertThat(bounds.low()).isEqualTo(0.701, TOLERANCE);
    }

    @Test
    void oneContraryOutcomeAfterAPerfectTenHoldsInsideTheHysteresisBand() {
        // 10/11 — the baseline's documented "holds" boundary (LB 0.623 sits between the 0.60
        // exit and the 0.70 enter threshold).
        WilsonInterval.Bounds bounds = WilsonInterval.score(10, 11);

        assertThat(bounds.low()).isEqualTo(0.623, TOLERANCE);
    }

    @Test
    void twoContraryOutcomesAfterAPerfectTenExitsLegitimately() {
        // 10/12 — the baseline's documented "exits" boundary (LB 0.552, below the 0.60 exit).
        WilsonInterval.Bounds bounds = WilsonInterval.score(10, 12);

        assertThat(bounds.low()).isEqualTo(0.552, TOLERANCE);
    }

    @Test
    void zeroRecordAtTheFloorOfTenClearsTheUnlikelyThreshold() {
        WilsonInterval.Bounds bounds = WilsonInterval.score(0, 10);

        assertThat(bounds.high()).isEqualTo(0.278, TOLERANCE);
    }

    @Test
    void zeroRecordAtNineSitsExactlyOnTheUnlikelyKnifeEdge() {
        WilsonInterval.Bounds bounds = WilsonInterval.score(0, 9);

        assertThat(bounds.high()).isEqualTo(0.299, TOLERANCE);
    }

    @Test
    void boundsAreClampedToTheUnitInterval() {
        WilsonInterval.Bounds perfect = WilsonInterval.score(5, 5);
        WilsonInterval.Bounds zero = WilsonInterval.score(0, 5);

        assertThat(perfect.high()).isLessThanOrEqualTo(1.0);
        assertThat(zero.low()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void zeroSampleSizeIsRejected() {
        assertThatThrownBy(() -> WilsonInterval.score(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

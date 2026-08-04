package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.dto.AttentionFactors;
import io.inspector.dto.SelfHealStats;
import io.inspector.selfheal.SelfHealLane;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the ONE-SENTENCE rationale (ALARM-COST-MODEL.md §4.3). The requirement is not "some
 * explanation exists" — it is that a single tooltip can carry it, with this card's real numbers,
 * and that an estimate under its own sample-size floor says "no history" rather than a number.
 */
class AttentionRationaleTest {

    @Test
    void theWorkedExampleFromTheDesignRendersVerbatim() {
        String sentence = sentence(21, 120, 14_400L, null, false);

        assertThat(sentence)
                .isEqualTo("21 failing · last seen 2 min ago · typically takes 4 h to resolve · no self-heal history.");
    }

    @Test
    void itIsOneSentenceOnOneLineSoATooltipCanHoldIt() {
        String sentence = sentence(4_312, 45, 90_000L, stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12), false);

        assertThat(sentence).doesNotContain("\n").endsWith(".");
        assertThat(sentence.chars().filter(c -> c == '.').count()).isEqualTo(1);
    }

    @Test
    void everySelfHealLaneGetsItsOwnEvidenceClauseWithTheRecordInIt() {
        assertThat(sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12), false))
                .contains("usually self-heals (12/14)");
        assertThat(sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_MIXED, 11, 6), false))
                .contains("mixed self-heal record (6/11)");
        assertThat(sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_UNLIKELY, 12, 1), false))
                .contains("rarely self-heals (1/12)");
        assertThat(sentence(1, 0, null, stats(SelfHealLane.INSUFFICIENT_HISTORY, 3, 1), false))
                .contains("no self-heal history");
    }

    @Test
    void aSubFloorEstimateNeverRendersAsANumber() {
        String sentence = sentence(8, 3_600, null, null, false);

        assertThat(sentence).contains("no resolve-time history").doesNotContain("typically takes");
    }

    @Test
    void aBrandNewSightingReadsAsJustNowRatherThanZeroSecondsAgo() {
        assertThat(sentence(8, 0, null, null, false)).contains("last seen just now");
        assertThat(sentence(8, 59, null, null, false)).contains("last seen just now");
        assertThat(sentence(8, 60, null, null, false)).contains("last seen 1 min ago");
    }

    @Test
    void magnitudesRenderInTheLargestUnitThatReachesOneWithoutRoundingIntoALie() {
        assertThat(AttentionRationale.humanize(45)).isEqualTo("45 s");
        assertThat(AttentionRationale.humanize(120)).isEqualTo("2 min");
        assertThat(AttentionRationale.humanize(5_400)).isEqualTo("1.5 h");
        assertThat(AttentionRationale.humanize(14_400)).isEqualTo("4 h");
        assertThat(AttentionRationale.humanize(86_400)).isEqualTo("1 d");
        assertThat(AttentionRationale.humanize(1_209_600)).isEqualTo("14 d");
    }

    /**
     * The pre-#365 five-arg shape, kept as a TEST-LOCAL factory: {@code sentence} now composes
     * itself from the score's own factor block, so every clause is guaranteed to quote the exact
     * numbers that produced the score rather than a second derivation of them.
     */
    private static String sentence(
            long liveTotal, long ageSeconds, Long medianMttrSeconds, SelfHealStats selfHeal, boolean arrivalsUnknown) {
        return AttentionRationale.sentence(
                liveTotal, factors(ageSeconds, medianMttrSeconds, arrivalsUnknown, false, 0L, false), selfHeal);
    }

    private static AttentionFactors factors(
            long ageSeconds,
            Long medianMttrSeconds,
            boolean arrivalsUnknown,
            boolean flooding,
            long burstArrivals,
            boolean burstUnknown) {
        return new AttentionFactors(
                1.0,
                1.0,
                1.0,
                1.0,
                0L,
                ageSeconds,
                medianMttrSeconds,
                0,
                null,
                false,
                arrivalsUnknown,
                0L,
                flooding,
                burstArrivals,
                600L,
                burstUnknown,
                0L);
    }

    @Test
    void aFloodingClassSaysHowManyArrivedAndOverWhatWindowRatherThanABareRatio() {
        String sentence = AttentionRationale.sentence(120, factors(30, null, false, true, 40L, false), null);

        assertThat(sentence).contains("spiking: 40 in the last 10 min");
        assertThat(sentence).doesNotContain("\n").endsWith(".");
        assertThat(sentence.chars().filter(c -> c == '.').count()).isEqualTo(1);
    }

    @Test
    void anUnknownBurstBinSaysUnknownRatherThanQuietAndNeverBothWithTheWiderWindowsClause() {
        assertThat(AttentionRationale.sentence(120, factors(30, null, false, false, 0L, true), null))
                .contains("recent arrival rate unknown");
        // A wholly-unknown 28d window already implies the last ten minutes are unknown too — one
        // sentence, so it says it ONCE, at the widest scope it actually measured.
        assertThat(AttentionRationale.sentence(120, factors(30, null, true, false, 0L, true), null))
                .contains("arrival volume unknown")
                .doesNotContain("recent arrival rate unknown");
    }

    private static SelfHealStats stats(SelfHealLane lane, int n, int healed) {
        return new SelfHealStats(lane.name(), n, healed, 0.4, 0.9, 300L, 600L, 0, false);
    }
}

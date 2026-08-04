package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;

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
        String sentence = AttentionRationale.sentence(21, 120, 14_400L, null);

        assertThat(sentence)
                .isEqualTo("21 failing · last seen 2 min ago · typically takes 4 h to resolve · no self-heal history.");
    }

    @Test
    void itIsOneSentenceOnOneLineSoATooltipCanHoldIt() {
        String sentence = AttentionRationale.sentence(4_312, 45, 90_000L, stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12));

        assertThat(sentence).doesNotContain("\n").endsWith(".");
        assertThat(sentence.chars().filter(c -> c == '.').count()).isEqualTo(1);
    }

    @Test
    void everySelfHealLaneGetsItsOwnEvidenceClauseWithTheRecordInIt() {
        assertThat(AttentionRationale.sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12)))
                .contains("usually self-heals (12/14)");
        assertThat(AttentionRationale.sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_MIXED, 11, 6)))
                .contains("mixed self-heal record (6/11)");
        assertThat(AttentionRationale.sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_UNLIKELY, 12, 1)))
                .contains("rarely self-heals (1/12)");
        assertThat(AttentionRationale.sentence(1, 0, null, stats(SelfHealLane.INSUFFICIENT_HISTORY, 3, 1)))
                .contains("no self-heal history");
    }

    @Test
    void aSubFloorEstimateNeverRendersAsANumber() {
        String sentence = AttentionRationale.sentence(8, 3_600, null, null);

        assertThat(sentence).contains("no resolve-time history").doesNotContain("typically takes");
    }

    @Test
    void aBrandNewSightingReadsAsJustNowRatherThanZeroSecondsAgo() {
        assertThat(AttentionRationale.sentence(8, 0, null, null)).contains("last seen just now");
        assertThat(AttentionRationale.sentence(8, 59, null, null)).contains("last seen just now");
        assertThat(AttentionRationale.sentence(8, 60, null, null)).contains("last seen 1 min ago");
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

    private static SelfHealStats stats(SelfHealLane lane, int n, int healed) {
        return new SelfHealStats(lane.name(), n, healed, 0.4, 0.9, 300L, 600L, 0, false);
    }
}

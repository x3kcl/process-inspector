package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.inspector.dto.AttentionScore;
import io.inspector.dto.SelfHealStats;
import io.inspector.selfheal.SelfHealLane;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the attention score's PURE math (ALARM-COST-MODEL.md §4.1) against SYNTHETIC inputs.
 *
 * <p>Synthetic on purpose. Measured against the real pilot ledger the score reorders NOTHING
 * (§5.5), so any fixture claiming otherwise would be fiction dressed as evidence. These tests
 * prove the formula behaves as specified when the data eventually exists — the §7 gate decides
 * when that is, not this file.
 */
class AttentionScoreCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final AttentionConfig CONFIG = AttentionConfig.defaults();

    /* ---------------- F: frequency = log2(1 + arrivals) ---------------- */

    @Test
    void frequencyIsLog2OfOnePlusArrivalsSoAThousandFoldSpikeIsNotAThousandFoldRanking() {
        assertThat(factorsFor(arrivals(0)).frequency()).isEqualTo(0.0);
        assertThat(factorsFor(arrivals(1)).frequency()).isEqualTo(1.0);
        assertThat(factorsFor(arrivals(3)).frequency()).isEqualTo(2.0);
        assertThat(factorsFor(arrivals(7)).frequency()).isEqualTo(3.0);
        assertThat(factorsFor(arrivals(1023)).frequency()).isEqualTo(10.0);
    }

    @Test
    void arrivalsAreTheGrowthSignalNotTheSizeSignal() {
        // The whole point of replacing count-only: a huge but static class has no ARRIVALS.
        AttentionScore staticButHuge = score(9_999, arrivals(0), null, null);
        AttentionScore smallButGrowing = score(8, arrivals(15), null, null);

        assertThat(smallButGrowing.score()).isGreaterThan(staticButHuge.score());
    }

    /* ---------------- R: recency = 2^(-age/tau) ---------------- */

    @Test
    void recencyHalvesEveryHalfLife() {
        assertThat(AttentionScoreCalculator.recency(0, Duration.ofHours(24))).isEqualTo(1.0);
        assertThat(AttentionScoreCalculator.recency(86_400, Duration.ofHours(24)))
                .isEqualTo(0.5);
        assertThat(AttentionScoreCalculator.recency(172_800, Duration.ofHours(24)))
                .isEqualTo(0.25);
    }

    @Test
    void aClassWithNoLedgerRowReadsAsFreshRatherThanAsInfinitelyOld() {
        // Never fabricate an age: an unknown class must not be buried by a factor of 2^(-huge).
        assertThat(factorsFor(ClassHistory.none()).recency()).isEqualTo(1.0);
        assertThat(factorsFor(ClassHistory.none()).ageSeconds()).isZero();
    }

    @Test
    void aFutureLastSeenClampsToZeroAgeRatherThanExceedingOne() {
        ClassHistory ahead = new ClassHistory(NOW.plusSeconds(3600), 0, List.of());

        assertThat(factorsFor(ahead).recency()).isEqualTo(1.0);
    }

    /* ---------------- M: historic cost, clamped, floored on sample size ---------------- */

    @Test
    void mttrIsNeutralAndTheMedianIsAbsentBelowTheClosedEpisodeFloor() {
        ClassHistory twoClosed = new ClassHistory(NOW, 5, List.of(3_600L, 7_200L));

        AttentionScore scored = score(10, twoClosed, 3_600L, null);

        assertThat(scored.factors().mttr()).isEqualTo(1.0);
        assertThat(scored.factors().medianMttrSeconds()).isNull(); // "no history", never a number
        assertThat(scored.factors().closedEpisodes()).isEqualTo(2);
        assertThat(scored.rationale()).contains("no resolve-time history");
    }

    @Test
    void atOrAboveTheFloorMttrIsTheClassMedianOverTheFleetMedian() {
        ClassHistory threeClosed = new ClassHistory(NOW, 5, List.of(3_600L, 5_400L, 7_200L));

        AttentionScore scored = score(10, threeClosed, 3_600L, null);

        assertThat(scored.factors().medianMttrSeconds()).isEqualTo(5_400L);
        assertThat(scored.factors().mttr()).isEqualTo(1.5);
        assertThat(scored.rationale()).contains("typically takes 1.5 h to resolve");
    }

    @Test
    void mttrIsClampedBothWaysSoOnePathologicalClassCannotDominateTheProduct() {
        ClassHistory glacial = new ClassHistory(NOW, 5, List.of(999_999L, 999_999L, 999_999L));
        ClassHistory instant = new ClassHistory(NOW, 5, List.of(1L, 1L, 1L));

        assertThat(score(10, glacial, 3_600L, null).factors().mttr()).isEqualTo(2.0);
        assertThat(score(10, instant, 3_600L, null).factors().mttr()).isEqualTo(0.5);
    }

    @Test
    void mttrStaysNeutralWhenTheFleetHasNeverClosedAnEpisode() {
        // The measured pilot state: 0 closed episodes fleet-wide ⇒ no denominator exists.
        ClassHistory threeClosed = new ClassHistory(NOW, 5, List.of(60L, 60L, 60L));

        assertThat(score(10, threeClosed, null, null).factors().mttr()).isEqualTo(1.0);
    }

    /* ---------------- S: self-heal demotion, from the STABILIZED lane ---------------- */

    @Test
    void anAbsentOrInsufficientLaneIsNeutralRatherThanADemotion() {
        assertThat(AttentionScoreCalculator.selfHealFactor(null, 0.25)).isEqualTo(1.0);
        assertThat(AttentionScoreCalculator.selfHealFactor(SelfHealLane.INSUFFICIENT_HISTORY, 0.25))
                .isEqualTo(1.0);
    }

    @Test
    void aProvenSelfHealerIsDemotedAtMostFourfoldAndNeverZeroed() {
        assertThat(AttentionScoreCalculator.selfHealFactor(SelfHealLane.SELF_HEAL_LIKELY, 0.25))
                .isEqualTo(0.25);
        assertThat(AttentionScoreCalculator.selfHealFactor(SelfHealLane.SELF_HEAL_MIXED, 0.25))
                .isEqualTo(0.5);
        assertThat(AttentionScoreCalculator.selfHealFactor(SelfHealLane.SELF_HEAL_UNLIKELY, 0.25))
                .isEqualTo(0.85);
    }

    @Test
    void theFloorIsWhatKeepsAMassSelfHealClassOnScreen() {
        // Same doctrine as never-hide: demote hard, never to zero.
        AttentionScore healer = score(500, arrivals(7), null, stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12));

        assertThat(healer.factors().selfHeal()).isEqualTo(0.25);
        assertThat(healer.score()).isGreaterThan(0);
    }

    @Test
    void anUnknownLaneStringReadsAsNoHistoryRatherThanAsARiskClaim() {
        SelfHealStats garbled = new SelfHealStats("SELF_HEAL_PROBABLY_MAYBE", 12, 6, null, null, null, null, 0, false);

        AttentionScore scored = score(10, arrivals(1), null, garbled);

        assertThat(scored.factors().selfHeal()).isEqualTo(1.0);
        assertThat(scored.factors().selfHealLane()).isNull();
        assertThat(scored.rationale()).contains("no self-heal history");
    }

    /* ---------------- the product, and the honesty flag ---------------- */

    @Test
    void theScoreIsTheProductOfTheFourFactors() {
        ClassHistory history = new ClassHistory(NOW.minusSeconds(86_400), 3, List.of(7_200L, 7_200L, 7_200L));

        AttentionScore scored = score(21, history, 3_600L, stats(SelfHealLane.SELF_HEAL_MIXED, 20, 10));

        // F=log2(4)=2 · R=2^-1=0.5 · M=clamp(7200/3600)=2 · S=0.5 = 1.0
        assertThat(scored.factors().frequency()).isEqualTo(2.0);
        assertThat(scored.factors().recency()).isEqualTo(0.5);
        assertThat(scored.factors().mttr()).isEqualTo(2.0);
        assertThat(scored.factors().selfHeal()).isEqualTo(0.5);
        assertThat(scored.score()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void insufficientHistoryIsTrueExactlyWhenNeitherDiscriminatingFactorHadEvidence() {
        assertThat(score(10, arrivals(5), null, null).factors().insufficientHistory())
                .isTrue();
        assertThat(score(10, new ClassHistory(NOW, 5, List.of(1L, 2L, 3L)), 2L, null)
                        .factors()
                        .insufficientHistory())
                .isFalse();
        assertThat(score(10, arrivals(5), null, stats(SelfHealLane.SELF_HEAL_MIXED, 12, 6))
                        .factors()
                        .insufficientHistory())
                .isFalse();
    }

    /* ---------------- C2: the ack-expiry suggestion (§3.2) ---------------- */

    @Test
    void thereIsNoAckExpirySuggestionBelowTheClosedEpisodeFloorWhichIsTodaysBehaviour() {
        assertThat(AttentionScoreCalculator.suggestedAckExpirySeconds(List.of(), CONFIG))
                .isNull();
        assertThat(AttentionScoreCalculator.suggestedAckExpirySeconds(List.of(60L, 120L), CONFIG))
                .isNull();
    }

    @Test
    void theAckExpirySuggestionIsTheClassP75ClosedEpisodeDuration() {
        List<Long> durations = List.of(3_600L, 7_200L, 10_800L, 14_400L);

        assertThat(AttentionScoreCalculator.suggestedAckExpirySeconds(durations, CONFIG))
                .isEqualTo(10_800L);
    }

    /* ---------------- fixtures ---------------- */

    private static ClassHistory arrivals(long count) {
        return new ClassHistory(NOW, count, List.of());
    }

    private static SelfHealStats stats(SelfHealLane lane, int n, int healed) {
        return new SelfHealStats(lane.name(), n, healed, 0.4, 0.9, 300L, 600L, 0, false);
    }

    private static io.inspector.dto.AttentionFactors factorsFor(ClassHistory history) {
        return score(10, history, null, null).factors();
    }

    private static AttentionScore score(long total, ClassHistory history, Long fleetMedian, SelfHealStats stats) {
        return AttentionScoreCalculator.score(total, history, fleetMedian, stats, CONFIG, NOW);
    }
}

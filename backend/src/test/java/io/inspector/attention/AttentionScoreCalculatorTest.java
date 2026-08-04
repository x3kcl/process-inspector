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
        // UNCHANGED by the review's FIX 3 (the SQL now seeds a 0 baseline for a class's own FIRST
        // EVER bucket, so a genuine 0 -> 5000 birth counts as 5000 arrivals): a class that merely
        // EXISTS at 9 999 with no in-window growth still reports 0, because its birth is outside
        // the window and every in-window delta is flat. "Big" and "grew" stay different claims.
        AttentionScore staticButHuge = score(9_999, arrivals(0), null, null);
        AttentionScore smallButGrowing = score(8, arrivals(15), null, null);

        assertThat(smallButGrowing.score()).isGreaterThan(staticButHuge.score());
    }

    /* ---------------- FIX 2: an UNTRUSTED window is unknown, never a proven zero ---------------- */

    @Test
    void aWhollyUntrustedArrivalWindowReadsNeutralRatherThanZeroingTheWholeScore() {
        // The confirmed defect. `isTruncated` marks a group truncated when ANY engine it touches
        // hit the failure-lane scan cap, so on an engine PERMANENTLY at the cap every group it
        // contributes is truncated in every bucket, every delta is discarded, arrivals lands on 0
        // and F = log2(1) = 0 — which zeroes A(c) = F*R*M*S whatever R, M and S say. Truncation
        // correlates with SIZE, so that demoted exactly the largest classes, silently.
        ClassHistory untrusted = new ClassHistory(NOW, 0L, true, 40_320L, List.of());

        assertThat(factorsFor(untrusted).frequency()).isEqualTo(1.0);
        assertThat(factorsFor(untrusted).arrivalsUnknown()).isTrue();
        assertThat(factorsFor(untrusted).discardedArrivalSamples()).isEqualTo(40_320L);
    }

    @Test
    void theBigTruncatedClassNoLongerSortsBelowTheOneMemberClassWithOneArrival() {
        // The review's concrete pair: X has 4 000 members on a capped engine and was only just
        // seen; Y has one member and one arrival on a small, healthy engine. Y used to win.
        AttentionScore cappedAndHuge = score(4_000, new ClassHistory(NOW, 0L, true, 500L, List.of()), null, null);
        AttentionScore tinyButMeasured = score(1, arrivals(1), null, null);

        assertThat(cappedAndHuge.score()).isEqualTo(1.0);
        assertThat(tinyButMeasured.score()).isEqualTo(1.0);
        // A tie, broken by live total DESC — not a demotion. The point is that "unknown" must not
        // read as "provably not growing"; it must not read as "growing fast" either.
        assertThat(cappedAndHuge.score()).isGreaterThanOrEqualTo(tinyButMeasured.score());
    }

    @Test
    void anUntrustedWindowSaysSoInTheRationaleInsteadOfLettingTheReaderAssumeItWasMeasured() {
        AttentionScore scored = score(4_000, new ClassHistory(NOW, 0L, true, 500L, List.of()), null, null);

        assertThat(scored.rationale()).contains("arrival volume unknown");
    }

    @Test
    void aPartiallyDiscardedWindowStillReportsTheArrivalsItCouldTrustAndSaysHowManyItLost() {
        ClassHistory partial = new ClassHistory(NOW, 7L, false, 12L, List.of());

        assertThat(factorsFor(partial).frequency()).isEqualTo(3.0); // log2(1+7)
        assertThat(factorsFor(partial).arrivalsUnknown()).isFalse();
        assertThat(factorsFor(partial).discardedArrivalSamples()).isEqualTo(12L);
    }

    @Test
    void aClassWithNoLedgerRowAtAllKeepsTheZeroFThatMakesTheWholeFleetTieToCountOnly() {
        // The deliberate asymmetry: "no evidence at all" is fleet-uniform and collapses everyone
        // to the count-only tie-break (the design's headline neutrality guarantee), while
        // "evidence we were not allowed to trust" correlates with size and must not demote.
        assertThat(factorsFor(ClassHistory.none()).frequency()).isEqualTo(0.0);
        assertThat(factorsFor(ClassHistory.none()).arrivalsUnknown()).isFalse();
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
        ClassHistory ahead = ClassHistory.observed(NOW.plusSeconds(3600), 0, List.of());

        assertThat(factorsFor(ahead).recency()).isEqualTo(1.0);
    }

    /* ---------------- M: historic cost, clamped, floored on sample size ---------------- */

    @Test
    void mttrIsNeutralAndTheMedianIsAbsentBelowTheClosedEpisodeFloor() {
        ClassHistory twoClosed = ClassHistory.observed(NOW, 5, List.of(3_600L, 7_200L));

        AttentionScore scored = score(10, twoClosed, 3_600L, null);

        assertThat(scored.factors().mttr()).isEqualTo(1.0);
        assertThat(scored.factors().medianMttrSeconds()).isNull(); // "no history", never a number
        assertThat(scored.factors().closedEpisodes()).isEqualTo(2);
        assertThat(scored.rationale()).contains("no resolve-time history");
    }

    @Test
    void atOrAboveTheFloorMttrIsTheClassMedianOverTheFleetMedian() {
        ClassHistory threeClosed = ClassHistory.observed(NOW, 5, List.of(3_600L, 5_400L, 7_200L));

        AttentionScore scored = score(10, threeClosed, 3_600L, null);

        assertThat(scored.factors().medianMttrSeconds()).isEqualTo(5_400L);
        assertThat(scored.factors().mttr()).isEqualTo(1.5);
        assertThat(scored.rationale()).contains("typically takes 1.5 h to resolve");
    }

    @Test
    void mttrIsClampedBothWaysSoOnePathologicalClassCannotDominateTheProduct() {
        ClassHistory glacial = ClassHistory.observed(NOW, 5, List.of(999_999L, 999_999L, 999_999L));
        ClassHistory instant = ClassHistory.observed(NOW, 5, List.of(1L, 1L, 1L));

        assertThat(score(10, glacial, 3_600L, null).factors().mttr()).isEqualTo(2.0);
        assertThat(score(10, instant, 3_600L, null).factors().mttr()).isEqualTo(0.5);
    }

    @Test
    void mttrStaysNeutralWhenTheFleetHasNeverClosedAnEpisode() {
        // The measured pilot state: 0 closed episodes fleet-wide ⇒ no denominator exists.
        ClassHistory threeClosed = ClassHistory.observed(NOW, 5, List.of(60L, 60L, 60L));

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
        ClassHistory history = ClassHistory.observed(NOW.minusSeconds(86_400), 3, List.of(7_200L, 7_200L, 7_200L));

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
        assertThat(score(10, ClassHistory.observed(NOW, 5, List.of(1L, 2L, 3L)), 2L, null)
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
        return ClassHistory.observed(NOW, count, List.of());
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

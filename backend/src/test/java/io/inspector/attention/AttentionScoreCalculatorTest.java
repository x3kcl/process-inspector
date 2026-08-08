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

    /* ---------------- #365: burst-aware F (§4.1a) — the gate, and what it may NOT do ---------- */

    @Test
    void aFloodRanksAboveATrickleOfTheVerySameTwentyEightDayVolume() {
        // The #365 gap, stated as a test: two classes, 100 arrivals each, both last seen NOW —
        // indistinguishable to the shipped F (log2(101) both ways) even though one of them put
        // its whole volume into the last ten minutes. ISA-18.2 calls that a flood.
        ClassHistory trickle = burst(100, 0, 0);
        ClassHistory flood = burst(100, 100, 0);

        assertThat(factorsFor(trickle).frequency()).isCloseTo(6.6582, within(1e-4));
        assertThat(factorsFor(flood).frequency()).isCloseTo(9.6457, within(1e-4)); // log2(1 + 8*100)
        assertThat(factorsFor(flood).frequency())
                .isGreaterThan(factorsFor(trickle).frequency());
        assertThat(factorsFor(flood).flooding()).isTrue();
        assertThat(factorsFor(trickle).flooding()).isFalse();
        assertThat(factorsFor(flood).burstArrivals()).isEqualTo(100);
        assertThat(factorsFor(flood).burstWindowSeconds()).isEqualTo(600);
    }

    @Test
    void aBirthInsideTheWindowIsWeighedOnceAtGammaRatherThanReBankedByASecondFactor() {
        // §13 F3 carried forward: `0 -> 5000` in one bucket IS the largest flood, and the
        // decomposition counts that population exactly ONCE, at weight gamma. The rejected
        // multiplier shape would have multiplied the shipped 12.288 by a clamp instead.
        ClassHistory birthFlood = burst(5_000, 5_000, 0);

        assertThat(factorsFor(birthFlood).frequency()).isCloseTo(15.2877, within(1e-4)); // log2(1+8*5000)
        assertThat(factorsFor(birthFlood).flooding()).isTrue();
    }

    @Test
    void theBoostIsSelfBoundedByTheLogSoNoSingleFactorCanRunAwayWithTheProduct() {
        // §4.1a's gamma rationale: the gate cannot fire below the onset, so F's multiplicative
        // inflation is bounded by 1 + log2(gamma)/log2(1 + onset) ~ 1.867 — the same order as the
        // M clamp's 2x, deliberately, so no one factor can dominate the others' full range.
        double ceiling = 1 + (Math.log(CONFIG.burstWeight()) / Math.log(2)) / shippedFrequency(CONFIG.burstOnset());
        assertThat(ceiling).isCloseTo(1.867, within(1e-3));

        // That figure is a BOUND, not an attained value: the tightest case is a flood sitting
        // exactly on the onset with nothing outside the window, and it lands at 1.833.
        double worstCase = AttentionScoreCalculator.frequency(burst(10, 10, 0), CONFIG) / shippedFrequency(10);
        assertThat(worstCase).isCloseTo(1.833, within(1e-3)).isLessThan(ceiling);

        // ...and the inflation SHRINKS as volume grows — a bigger flood is inflated relatively less.
        assertThat(AttentionScoreCalculator.frequency(burst(100, 100, 0), CONFIG) / shippedFrequency(100))
                .isCloseTo(1.4487, within(1e-4))
                .isLessThan(worstCase);
    }

    @Test
    void theBoostCanNeverAddMoreThanLog2GammaBitsWhateverTheBinsSay() {
        // The unconditional form of the bound above, which does not lean on the gate at all: the
        // decomposition replaces at most `arrivals` with `gamma * arrivals`, so F grows by at most
        // log2(gamma) = 3 bits. Swept over every gating shape, valid or degenerate.
        double maxBits = Math.log(CONFIG.burstWeight()) / Math.log(2);
        for (long arrivals : new long[] {1, 5, 10, 11, 15, 100, 5_000, 1_000_000}) {
            for (long burst : new long[] {0, 1, 5, 10, 11, 100, 5_000, 1_000_000}) {
                for (long prior : new long[] {0, 10, 100}) {
                    double actual = AttentionScoreCalculator.frequency(burst(arrivals, burst, prior), CONFIG);

                    assertThat(actual - shippedFrequency(arrivals))
                            .as("F uplift for arrivals=%d burst=%d prior=%d", arrivals, burst, prior)
                            .isBetween(0.0, maxBits + 1e-9);
                }
            }
        }
    }

    @Test
    void belowTheOnsetTheAmendmentIsByteIdenticalToTheShippedFormula() {
        // The §5.5 neutrality guarantee's load-bearing claim, proven at the BIT level rather than
        // "close to": outside flood conditions the amendment must be provably inert, or the whole
        // no-history-degrades-to-count-only argument (and AttentionOrderingNeutralityTest) moves.
        for (long arrivals : new long[] {0, 1, 2, 3, 7, 9, 10, 50, 100, 1023, 5_000, 999_999}) {
            for (long burst = 0; burst < 10; burst++) { // every sub-onset burst...
                for (long prior = 0; prior < 10; prior++) { // ...against every sub-onset prior
                    if (burst > arrivals) {
                        continue;
                    }
                    double actual = AttentionScoreCalculator.frequency(burst(arrivals, burst, prior), CONFIG);

                    assertThat(Double.doubleToRawLongBits(actual))
                            .as("F(arrivals=%d, burst=%d, prior=%d) must be the shipped bits", arrivals, burst, prior)
                            .isEqualTo(Double.doubleToRawLongBits(shippedFrequency(arrivals)));
                }
            }
        }
    }

    @Test
    void theGateIsISA182sAsymmetricSchmittTrigger() {
        // Entry needs a genuine 10-minute onset (>= 10); the hold leg keeps a decaying flood up
        // while it stays at or above the exit (5) AND the onset sits in the PRIOR window.
        assertThat(flooding(12, 0)).isTrue(); // entry: 12 in W
        assertThat(flooding(7, 12)).isTrue(); // hold: 7 now, onset last window
        assertThat(flooding(5, 12)).isTrue(); // hold, exactly AT the exit threshold
        assertThat(flooding(4, 12)).isFalse(); // drop: below the exit
        assertThat(flooding(10, 0)).isTrue(); // entry, exactly AT the onset
        assertThat(flooding(9, 0)).isFalse(); // one short of the onset
        assertThat(flooding(0, 0)).isFalse(); // empty bins ⇒ never
    }

    @Test
    void theHoldLegNeedsAGENUINEPriorOnsetSoTwoHalfFloodsCannotGateThroughTheBackDoor() {
        // The author's own adversarial finding (§14.6): summing the bins would let 6 + 6 across
        // TWENTY minutes gate as a flood that never had a ten-minute onset. `prior_W >= onset`
        // alone closes that door.
        assertThat(flooding(6, 6)).isFalse();
        assertThat(AttentionScoreCalculator.frequency(burst(100, 6, 6), CONFIG)).isEqualTo(shippedFrequency(100));
        // ...while a prior window that DID reach onset still holds the very same current bin up.
        assertThat(flooding(6, 10)).isTrue();
    }

    @Test
    void anUnknownBurstBinForcesTheGateOffAndCanNeverLowerTheShippedF() {
        // §13 F2's discipline, inherited: a burst bin with samples but no TRUSTED one is UNKNOWN.
        // Because the burst term only ever RAISES F behind the gate, unknown can suppress a
        // promotion — never a demotion, which is strictly safer than the F2 defect class.
        ClassHistory unknownBin = new ClassHistory(NOW, 100L, false, 0L, 0L, 0L, true, 240L, List.of());

        assertThat(factorsFor(unknownBin).frequency()).isEqualTo(shippedFrequency(100));
        assertThat(factorsFor(unknownBin).flooding()).isFalse();
        assertThat(factorsFor(unknownBin).burstUnknown()).isTrue();
        assertThat(factorsFor(unknownBin).discardedBurstSamples()).isEqualTo(240L);
        assertThat(factorsFor(unknownBin).frequency()).isGreaterThanOrEqualTo(0.0);
        assertThat(factorsFor(unknownBin).frequency())
                .isEqualTo(factorsFor(burst(100, 0, 0)).frequency()); // never below the shipped value
    }

    @Test
    void anUnknownBurstBinCannotFakeAFloodEitherEvenWithABurstCountSittingInTheRow() {
        // Belt and braces: if a row ever carried both an untrusted bin AND a count, the count is
        // not evidence — it was summed from deltas we already refused to trust.
        ClassHistory contradictory = new ClassHistory(NOW, 100L, false, 0L, 40L, 40L, true, 240L, List.of());

        assertThat(factorsFor(contradictory).flooding()).isFalse();
        assertThat(factorsFor(contradictory).frequency()).isEqualTo(shippedFrequency(100));
    }

    @Test
    void aWhollyUnknownArrivalWindowStillReadsTheNeutralOneAndNeverGatesAFlood() {
        // §14.4 scenario (c): the burst bin is a SUBSET of the 28d window, so an unknown window
        // cannot host a knowable flood. F stays the multiplicative identity — never a fake zero,
        // and never a fake spike.
        ClassHistory whollyUnknown = new ClassHistory(NOW, 0L, true, 40_320L, 0L, 0L, true, 1_440L, List.of());

        assertThat(factorsFor(whollyUnknown).frequency()).isEqualTo(1.0);
        assertThat(factorsFor(whollyUnknown).flooding()).isFalse();
    }

    @Test
    void anArrivalsUnknownWindowCannotBeGatedByAContradictoryTrustedBurstBinEither() {
        // Defence in depth for a shape the SQL cannot produce (bin ⊆ window): if it ever did,
        // "unknown volume" must still win over "a bin that claims a flood".
        ClassHistory contradictory = new ClassHistory(NOW, 0L, true, 40_320L, 50L, 0L, false, 0L, List.of());

        assertThat(factorsFor(contradictory).frequency()).isEqualTo(1.0);
        assertThat(factorsFor(contradictory).flooding()).isFalse();
    }

    @Test
    void aBurstBinLargerThanItsOwnWindowIsClampedRatherThanAllowedToInventNegativeVolume() {
        // The partition identity is `outside = arrivals - burst`, so a burst exceeding arrivals
        // would manufacture NEGATIVE outside volume. The SQL cannot produce it (same filters,
        // narrower time — asserted in LedgerNativeQueriesIT), so this is the arithmetic backstop.
        ClassHistory impossible = burst(10, 40, 0);

        assertThat(factorsFor(impossible).frequency()).isCloseTo(6.3399, within(1e-4)); // log2(1+8*10)
        assertThat(factorsFor(impossible).burstArrivals()).isEqualTo(10);
    }

    @Test
    void theRationaleSaysHowManyLandedInTheLastTenMinutesRatherThanABareRatio() {
        AttentionScore spiking = score(120, burst(100, 40, 0), null, null);

        assertThat(spiking.rationale()).contains("spiking: 40 in the last 10 min");
    }

    @Test
    void anUnknownBurstBinSaysTheRecentRateIsUnknownRatherThanImplyingItIsQuiet() {
        AttentionScore unknownBin =
                score(120, new ClassHistory(NOW, 100L, false, 0L, 0L, 0L, true, 240L, List.of()), null, null);

        assertThat(unknownBin.rationale())
                .contains("recent arrival rate unknown")
                .doesNotContain("spiking");
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
    void theSevenArgOverloadThreadsDeadLetterEvidenceIntoTheRationaleAndTheSixArgOverloadStaysAbsent() {
        // #388's calculator-level contract: the extra overload is the ONLY place a non-ABSENT
        // DeadLetterEvidence can reach AttentionRationale.sentence(); the pre-existing six-arg
        // overload (used by every other test in this file) must keep composing the base clause.
        SelfHealStats likely = stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12);
        AttentionRationale.DeadLetterEvidence trusted = new AttentionRationale.DeadLetterEvidence(9L, 0L, true);

        AttentionScore withEvidence =
                AttentionScoreCalculator.score(10, arrivals(1), null, likely, CONFIG, NOW, trusted);
        AttentionScore withoutEvidence = AttentionScoreCalculator.score(10, arrivals(1), null, likely, CONFIG, NOW);

        assertThat(withEvidence.rationale()).contains("— not the 9 dead-lettered (no retries left)");
        assertThat(withoutEvidence.rationale()).doesNotContain("dead-lettered");
        // The DeadLetterEvidence parameter is copy-only — it must not perturb the score itself.
        assertThat(withEvidence.score()).isEqualTo(withoutEvidence.score());
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

    /** A fully-observed window whose deltas split into the two burst bins (§4.1a). */
    private static ClassHistory burst(long arrivals, long burstArrivals, long priorBurstArrivals) {
        return new ClassHistory(NOW, arrivals, false, 0L, burstArrivals, priorBurstArrivals, false, 0L, List.of());
    }

    private static boolean flooding(long burstArrivals, long priorBurstArrivals) {
        return AttentionScoreCalculator.flooding(burst(1_000, burstArrivals, priorBurstArrivals), CONFIG);
    }

    /**
     * The SHIPPED (pre-#365) F, recomputed here independently: {@code log2(1 + arrivals)}. Nothing
     * in this fixture reads production code, so "byte-identical below the onset" is a claim about
     * the amendment rather than a tautology.
     */
    private static double shippedFrequency(long arrivals) {
        return Math.log(1 + arrivals) / Math.log(2);
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

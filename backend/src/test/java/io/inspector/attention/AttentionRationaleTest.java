package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.attention.AttentionRationale.DeadLetterEvidence;
import io.inspector.dto.AttentionFactors;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.SelfHealStats;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.selfheal.SelfHealLane;
import java.util.Map;
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

        // #399: the resolve-time clause names BOTH ends of the statistic. The median is
        // first-sighting → operator-resolve, queue wait included, so "typically takes 4 h to
        // resolve" was a fix-time claim the ledger never measured (ALARM-COST-MODEL §4.3/§17).
        assertThat(sentence)
                .isEqualTo("21 failing · last seen 2 min ago · typically 4 h from first sighting to resolve"
                        + " · no self-heal history.");
    }

    @Test
    void itIsOneSentenceOnOneLineSoATooltipCanHoldIt() {
        String sentence = sentence(4_312, 45, 90_000L, stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12), false);

        assertThat(sentence).doesNotContain("\n").endsWith(".");
        assertThat(sentence.chars().filter(c -> c == '.').count()).isEqualTo(1);
    }

    /**
     * PR #394 review: the CAP is "one sentence, one tooltip" — every optional clause this class
     * can emit must be able to coexist without breaking that, not just the common cases the other
     * tests exercise individually. Stacks every optional clause the composer can produce at once
     * (the #365 burst clause, the arrivals-unknown honesty clause, AND the #388 population
     * suffix on top of the SELF_HEAL_LIKELY base clause) and re-asserts the same one-line/
     * one-period invariant under the maximum load, not just the minimal case above.
     */
    @Test
    void staysOneSentenceOnOneLineWithTrustedSuffixEvidenceFloodingAndArrivalsUnknownAllPresentAtOnce() {
        SelfHealStats likely = stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12);
        DeadLetterEvidence trusted = new DeadLetterEvidence(9L, 0L, true);
        AttentionFactors factors = factors(45, 90_000L, true, true, 40L, false);

        String sentence = AttentionRationale.sentence(4_312, factors, likely, trusted);

        assertThat(sentence).doesNotContain("\n").endsWith(".");
        assertThat(sentence.chars().filter(c -> c == '.').count()).isEqualTo(1);
        assertThat(sentence)
                .contains("spiking: 40 in the last")
                .contains("arrival volume unknown")
                .contains("usually self-heals (12/14 past spells")
                .contains("— not the 9 dead-lettered (no retries left)");
    }

    @Test
    void everySelfHealLaneGetsItsOwnEvidenceClauseWithTheRecordInIt() {
        // stats() below sets ttsP90Seconds=600s (10 min) — SELF_HEAL_LIKELY therefore carries both
        // the #387 unit word and the timing half; the other lanes are untouched by #387.
        assertThat(sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12), false))
                .contains("usually self-heals (12/14 past spells, typically ≤ 10 min)");
        assertThat(sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_MIXED, 11, 6), false))
                .contains("mixed self-heal record (6/11)");
        assertThat(sentence(1, 0, null, stats(SelfHealLane.SELF_HEAL_UNLIKELY, 12, 1), false))
                .contains("rarely self-heals (1/12)");
        assertThat(sentence(1, 0, null, stats(SelfHealLane.INSUFFICIENT_HISTORY, 3, 1), false))
                .contains("no self-heal history");
    }

    /**
     * #387: Stage 0's rationale used to drop the timing half and the "spells" unit word that
     * /incidents' SelfHealBadge already carried (frontend/src/incidents/selfHeal.ts,
     * SELF_HEAL_LIKELY case) — same lane, same server-served {@code ttsP90Seconds}, two different
     * strings. This locks BOTH variants (with timing, without) so the two surfaces cannot silently
     * diverge again; frontend/src/incidents/selfHeal.test.ts's matching "cannot drift" test mirrors
     * the same n/healed/ttsP90 inputs and asserts the identical string.
     */
    @Test
    void selfHealLikelyMirrorsTheIncidentsBadgeExactlyWithAndWithoutTiming() {
        SelfHealStats withTiming =
                new SelfHealStats(SelfHealLane.SELF_HEAL_LIKELY.name(), 23, 21, 0.732, 0.98, 0L, 60L, 2, false);
        assertThat(sentence(1, 0, null, withTiming, false))
                .contains("usually self-heals (21/23 past spells, typically ≤ 1 min)");

        SelfHealStats withoutTiming =
                new SelfHealStats(SelfHealLane.SELF_HEAL_LIKELY.name(), 23, 21, 0.732, 0.98, null, null, 2, false);
        assertThat(sentence(1, 0, null, withoutTiming, false))
                .contains("usually self-heals (21/23 past spells)")
                .doesNotContain("typically");
    }

    /* ---------------- #388: the population-aware suffix ---------------- */

    /**
     * T4's own scenario (ALARM-COST-MODEL.md §8.9 finding 2 / issue #388): a LIKELY class
     * showing 25 dead-lettered / 0 retrying on the card face, the same n=23/healed=21 worked
     * example #387/#393 already locked, with 1-minute timing. This is the exact string the
     * build's Definition of Done asks to be reported.
     */
    @Test
    void theT4ScenarioComposesTheBaseClausePlusTheLockedSuffixVerbatim() {
        SelfHealStats stats =
                new SelfHealStats(SelfHealLane.SELF_HEAL_LIKELY.name(), 23, 21, 0.732, 0.98, 0L, 60L, 2, false);
        DeadLetterEvidence trusted = new DeadLetterEvidence(25L, 0L, true);

        String sentence = AttentionRationale.sentence(1, factors(0, null, false, false, 0L, false), stats, trusted);

        assertThat(sentence)
                .contains("usually self-heals (21/23 past spells, typically ≤ 1 min)"
                        + " — not the 25 dead-lettered (no retries left)");
    }

    @Test
    void theSuffixTriggersOnDeadLetterCountAloneIndependentOfRetryingCount() {
        SelfHealStats likely = stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12);

        // Retrying > 0 alongside standing dead-letters is routine (RETRYING-RISK-LANE.md §3.1) —
        // the REQUEST-CHANGES-rejected v1 shape keyed on retrying == 0; the locked design does not.
        String withLiveSpell = AttentionRationale.sentence(
                1, factors(0, null, false, false, 0L, false), likely, new DeadLetterEvidence(3L, 5L, true));
        assertThat(withLiveSpell).contains("— not the 3 dead-lettered (no retries left)");

        String withNoLiveSpell = AttentionRationale.sentence(
                1, factors(0, null, false, false, 0L, false), likely, new DeadLetterEvidence(3L, 0L, true));
        assertThat(withNoLiveSpell).contains("— not the 3 dead-lettered (no retries left)");
    }

    @Test
    void theSuffixNeverRendersWhenDeadLetterCountIsZeroOrNull() {
        SelfHealStats likely = stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12);

        assertThat(AttentionRationale.sentence(
                        1, factors(0, null, false, false, 0L, false), likely, new DeadLetterEvidence(0L, 0L, true)))
                .doesNotContain("dead-lettered");
        assertThat(AttentionRationale.sentence(
                        1,
                        factors(0, null, false, false, 0L, false),
                        likely,
                        new DeadLetterEvidence(null, null, false)))
                .doesNotContain("dead-lettered");
    }

    @Test
    void theSuffixNeverRendersOnAnUntrustedSplitEvenWithAPositiveCount() {
        SelfHealStats likely = stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12);

        // countsTrusted=false with a positive count — the shape a truncated/partial-scope caller
        // would (incorrectly) hand in if the trust bit were ignored.
        String sentence = AttentionRationale.sentence(
                1, factors(0, null, false, false, 0L, false), likely, new DeadLetterEvidence(25L, 0L, false));

        assertThat(sentence).doesNotContain("dead-lettered").contains("usually self-heals (12/14 past spells");
    }

    @Test
    void theAbsentEvidenceConstantNeverRendersTheSuffix() {
        SelfHealStats likely = stats(SelfHealLane.SELF_HEAL_LIKELY, 14, 12);

        assertThat(AttentionRationale.sentence(
                        1, factors(0, null, false, false, 0L, false), likely, DeadLetterEvidence.ABSENT))
                .doesNotContain("dead-lettered");
        // The 3-arg convenience overload is exactly this: base clause, always.
        assertThat(AttentionRationale.sentence(1, factors(0, null, false, false, 0L, false), likely))
                .doesNotContain("dead-lettered");
    }

    @Test
    void theSuffixIsScopedToTheLikelyLaneOnlyEvenWhenTriggerConditionsAreMet() {
        DeadLetterEvidence trusted = new DeadLetterEvidence(9L, 0L, true);
        AttentionFactors factors = factors(0, null, false, false, 0L, false);

        assertThat(AttentionRationale.sentence(1, factors, stats(SelfHealLane.SELF_HEAL_MIXED, 11, 6), trusted))
                .isEqualTo(AttentionRationale.sentence(
                        1, factors, stats(SelfHealLane.SELF_HEAL_MIXED, 11, 6), DeadLetterEvidence.ABSENT))
                .doesNotContain("dead-lettered");
        assertThat(AttentionRationale.sentence(1, factors, stats(SelfHealLane.SELF_HEAL_UNLIKELY, 12, 1), trusted))
                .doesNotContain("dead-lettered");
        assertThat(AttentionRationale.sentence(1, factors, stats(SelfHealLane.INSUFFICIENT_HISTORY, 3, 1), trusted))
                .doesNotContain("dead-lettered");
    }

    @Test
    void deadLetterEvidenceOfReadsAbsentForANullGroup() {
        assertThat(DeadLetterEvidence.of(null, Map.of())).isEqualTo(DeadLetterEvidence.ABSENT);
    }

    @Test
    void deadLetterEvidenceOfIsUntrustedWhenTheScopeProjectorNulledTheSplit() {
        // TriageScopeProjector nulls BOTH counts on a partially-scoped group (R-SAFE-17) — the
        // fleet-wide N must never leak to a scoped viewer, so this must fail toward the base
        // clause regardless of engine trust.
        ErrorGroup partiallyScoped = new ErrorGroup(
                "h", 2, "X", "msg", "raw", 10, null, null, Map.of("engine-a", Map.of("k:v1", 10L)), null);
        Map<String, PerEngineTriage> perEngine =
                Map.of("engine-a", new PerEngineTriage(true, null, "complete", 0, false));

        DeadLetterEvidence evidence = DeadLetterEvidence.of(partiallyScoped, perEngine);

        assertThat(evidence.countsTrusted()).isFalse();
        assertThat(evidence.deadLetterCount()).isNull();
        assertThat(evidence.retryingCount()).isNull();
    }

    @Test
    void deadLetterEvidenceOfIsUntrustedWhenAnyTouchedEngineScanIsTruncated() {
        // Even a timer/executable-only truncation suppresses the suffix (the #388 fold-in 3
        // deliberate conflation) — never just a narrower deadletterTruncated check.
        ErrorGroup group = new ErrorGroup(
                "h",
                2,
                "X",
                "msg",
                "raw",
                30,
                25L,
                0L,
                Map.of("engine-a", Map.of("k:v1", 20L), "engine-b", Map.of("k:v1", 10L)));
        Map<String, PerEngineTriage> perEngine = Map.of(
                "engine-a", new PerEngineTriage(true, null, "complete", 0, false),
                "engine-b", new PerEngineTriage(true, null, "truncated@500", 0, false));

        assertThat(DeadLetterEvidence.of(group, perEngine).countsTrusted()).isFalse();
    }

    @Test
    void deadLetterEvidenceOfIsUntrustedWhenATouchedEngineIsDownOrMissingFromPerEngine() {
        ErrorGroup group =
                new ErrorGroup("h", 2, "X", "msg", "raw", 10, 10L, 0L, Map.of("engine-a", Map.of("k:v1", 10L)));

        assertThat(DeadLetterEvidence.of(group, Map.of()).countsTrusted()).isFalse(); // missing entirely
        assertThat(DeadLetterEvidence.of(
                                group, Map.of("engine-a", new PerEngineTriage(false, "down", null, null, false)))
                        .countsTrusted())
                .isFalse(); // ok=false
    }

    @Test
    void deadLetterEvidenceOfIsTrustedWhenEveryTouchedEngineIsOkAndUntruncated() {
        ErrorGroup group = new ErrorGroup(
                "h",
                2,
                "X",
                "msg",
                "raw",
                30,
                25L,
                0L,
                Map.of("engine-a", Map.of("k:v1", 20L), "engine-b", Map.of("k:v1", 10L)));
        Map<String, PerEngineTriage> perEngine = Map.of(
                "engine-a", new PerEngineTriage(true, null, "complete", 0, false),
                "engine-b", new PerEngineTriage(true, null, "complete", 0, false));

        DeadLetterEvidence evidence = DeadLetterEvidence.of(group, perEngine);

        assertThat(evidence.countsTrusted()).isTrue();
        assertThat(evidence.deadLetterCount()).isEqualTo(25L);
        assertThat(evidence.retryingCount()).isEqualTo(0L);
    }

    @Test
    void minutesCeilRoundsUpSoTheBoundNeverUnderstates() {
        // 0s is a defensive edge (the DTO shouldn't serve a zero-second p90 for a healed spell in
        // practice) — never below 1 minute regardless (PR #393 review, zero-edge).
        assertThat(AttentionRationale.minutesCeil(0)).isEqualTo(1);
        assertThat(AttentionRationale.minutesCeil(45)).isEqualTo(1);
        assertThat(AttentionRationale.minutesCeil(60)).isEqualTo(1);
        assertThat(AttentionRationale.minutesCeil(61)).isEqualTo(2);
        assertThat(AttentionRationale.minutesCeil(480)).isEqualTo(8);
        assertThat(AttentionRationale.minutesCeil(481)).isEqualTo(9);
    }

    @Test
    void aSubFloorEstimateNeverRendersAsANumber() {
        String sentence = sentence(8, 3_600, null, null, false);

        assertThat(sentence).contains("no resolve-time history").doesNotContain("from first sighting");
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

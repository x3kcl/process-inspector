package io.inspector.selfheal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: spell extraction over synthetic fixtures (RETRYING-RISK-LANE.md §3.1) — no real
 * self-heal data needed or available (docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md: zero
 * unconfounded completed spells exist anywhere in the pilot ledger today). Every scenario here
 * is a plain {@link SpellSample} fixture at a fixed 60s bucket width.
 */
class RetrySpellExtractorTest {

    private static final Instant T0 = Instant.parse("2026-08-04T00:00:00Z");
    private static final Duration BUCKET = Duration.ofSeconds(60);

    private static Instant at(int minuteOffset) {
        return T0.plus(Duration.ofMinutes(minuteOffset));
    }

    /**
     * The steady-state observation SCOPE for every pre-#372 fixture: one unchanging two-engine
     * fleet, canonical form (sorted, comma-joined). Holding it CONSTANT is what keeps those
     * fixtures' outcomes byte-identical to the pre-#372 suite — the scope rule only bites on a
     * composition CHANGE or on an unrecorded scope.
     */
    private static final String FLEET = "engine-a,engine-b";

    /** The same class after engine-b is DISABLED in the registry — a smaller, honest scope. */
    private static final String FLEET_AFTER_DISABLE = "engine-a";

    private static SpellSample sample(int minute, long dlq, long retrying) {
        return new SpellSample(at(minute), dlq, retrying, false, true, FLEET);
    }

    private static SpellSample truncated(int minute, long dlq, long retrying) {
        return new SpellSample(at(minute), dlq, retrying, true, true, FLEET);
    }

    /** A row written while an engine was unreachable (#302): counts may be missing members. */
    private static SpellSample blind(int minute, long dlq, long retrying) {
        return new SpellSample(at(minute), dlq, retrying, false, false, FLEET);
    }

    /** A row written by a pass whose ENABLED fleet was {@code fleet} (#372, V22). */
    private static SpellSample scoped(int minute, long dlq, long retrying, String fleet) {
        return new SpellSample(at(minute), dlq, retrying, false, true, fleet);
    }

    @Test
    void aSelfHealedSpellHasDeadLetterCountUnchangedThroughTheLookahead() {
        // retrying 0->2->1->0(end)->+1 lookahead: DLQ never grows.
        List<SpellSample> samples =
                List.of(sample(0, 5, 0), sample(1, 5, 2), sample(2, 5, 1), sample(3, 5, 0), sample(4, 5, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        RetrySpell spell = spells.get(0);
        assertThat(spell.outcome()).isEqualTo(RetrySpell.Outcome.SELF_HEALED);
        assertThat(spell.live()).isFalse();
        assertThat(spell.confounded()).isFalse();
        assertThat(spell.gapVoided()).isFalse();
        assertThat(spell.truncationTainted()).isFalse();
        assertThat(spell.countable()).isTrue();
        assertThat(spell.start()).isEqualTo(at(1));
        assertThat(spell.end()).isEqualTo(at(2));
    }

    @Test
    void standingDeadLetterCountAtSpellStartDoesNotDisqualifyASelfHeal() {
        // §3.1: a non-zero DLQ AT spell start does not disqualify — only the DELTA matters.
        List<SpellSample> samples = List.of(sample(0, 20, 0), sample(1, 20, 3), sample(2, 20, 0), sample(3, 20, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).outcome()).isEqualTo(RetrySpell.Outcome.SELF_HEALED);
    }

    @Test
    void anEscalatedSpellHasDeadLetterCountGrowThroughTheLookahead() {
        List<SpellSample> samples = List.of(sample(0, 4, 0), sample(1, 4, 2), sample(2, 4, 0), sample(3, 8, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).outcome()).isEqualTo(RetrySpell.Outcome.ESCALATED);
        assertThat(spells.get(0).countable()).isTrue();
    }

    @Test
    void aSpellStillOpenAtSeriesEndIsLiveAndNeverCountable() {
        List<SpellSample> samples = List.of(sample(0, 4, 0), sample(1, 4, 2), sample(2, 4, 3));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        RetrySpell spell = spells.get(0);
        assertThat(spell.live()).isTrue();
        assertThat(spell.countable()).isFalse();
        assertThat(spell.excluded()).isFalse(); // live is its own bucket, not an "excluded completed" one
    }

    @Test
    void missingLookaheadSampleVoidsTheSpellRatherThanGuessingAnOutcome() {
        // ends at minute 2 (retrying->0), but the series stops there — no +1-bucket sample.
        List<SpellSample> samples = List.of(sample(0, 4, 0), sample(1, 4, 2), sample(2, 4, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        RetrySpell spell = spells.get(0);
        assertThat(spell.live()).isFalse();
        assertThat(spell.outcome()).isEqualTo(RetrySpell.Outcome.UNKNOWN);
        assertThat(spell.gapVoided()).isTrue();
        assertThat(spell.excluded()).isTrue();
        assertThat(spell.countable()).isFalse();
    }

    @Test
    void aSamplingGapInsideTheSpellVoidsIt() {
        // retrying at minute 0, then a 10-minute gap (>5 buckets) before it resumes.
        List<SpellSample> samples = List.of(sample(0, 4, 2), sample(10, 4, 2), sample(11, 4, 0), sample(12, 4, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).gapVoided()).isTrue();
        assertThat(spells.get(0).excluded()).isTrue();
    }

    @Test
    void aGapOfExactlyFiveBucketsDoesNotVoidTheSpell() {
        // exactly at the boundary (">5 buckets" per §3.1 — 5 itself must NOT void).
        List<SpellSample> samples = List.of(sample(0, 4, 2), sample(5, 4, 2), sample(6, 4, 0), sample(7, 4, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).gapVoided()).isFalse();
    }

    @Test
    void anyTruncatedSampleInTheSpellTaintsIt() {
        List<SpellSample> samples = List.of(sample(0, 4, 0), truncated(1, 4, 2), sample(2, 4, 0), sample(3, 4, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).truncationTainted()).isTrue();
        assertThat(spells.get(0).excluded()).isTrue();
    }

    @Test
    void aSpellOverlappingAConfoundWindowIsExcludedAsConfounded() {
        List<SpellSample> samples = List.of(sample(0, 4, 0), sample(1, 4, 2), sample(2, 4, 0), sample(3, 4, 0));
        // a retry-job audit row landed right at spell start.
        ConfoundWindow window =
                new ConfoundWindow(at(1).minus(BUCKET.multipliedBy(2)), at(1).plus(BUCKET.multipliedBy(2)));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of(window));

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).confounded()).isTrue();
        assertThat(spells.get(0).excluded()).isTrue();
    }

    @Test
    void aConfoundWindowFarFromTheSpellDoesNotExcludeIt() {
        List<SpellSample> samples = List.of(sample(0, 4, 0), sample(1, 4, 2), sample(2, 4, 0), sample(3, 4, 0));
        ConfoundWindow farAway = new ConfoundWindow(at(100), at(101));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of(farAway));

        assertThat(spells.get(0).confounded()).isFalse();
    }

    @Test
    void multipleSpellsSeparatedByAZeroBucketAreNeverMergedIntoOne() {
        List<SpellSample> samples = List.of(
                sample(0, 4, 0),
                sample(1, 4, 2),
                sample(2, 4, 0), // spell 1 ends
                sample(3, 4, 0), // lookahead for spell 1
                sample(4, 4, 1),
                sample(5, 4, 0), // spell 2 ends
                sample(6, 4, 0)); // lookahead for spell 2

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(2);
        assertThat(spells.get(0).start()).isEqualTo(at(1));
        assertThat(spells.get(1).start()).isEqualTo(at(4));
    }

    /* ---------------- #302 blind cycles: an outage is not evidence ---------------- */

    @Test
    void anEngineOutageThatForgesASpellEndIsVoidedNotRecordedAsSelfHealed() {
        // The live shape from the review: the class has RETRYING jobs on engine B and standing
        // dead-letters on engine A. B is unreachable for ONE bucket — the group is still present
        // (A's members keep total > 0) so a row IS written, but with retryingCount = 0 because
        // B's retrying members are simply missing. The edge detector sees the spell "end"; the
        // +1 look-ahead compares A's UNCHANGED dlq against spell start and finds no growth.
        // Before the fix that is Outcome.SELF_HEALED, countable — an outage entered into n as
        // evidence of autonomous healing, with nothing marking it tainted.
        List<SpellSample> samples = List.of(
                sample(0, 12, 0),
                sample(1, 12, 4),
                sample(2, 12, 4),
                blind(3, 12, 0), // engine B unreachable — NOT "the retries finished"
                sample(4, 12, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        RetrySpell spell = spells.get(0);
        assertThat(spell.gapVoided()).isTrue();
        assertThat(spell.excluded()).isTrue();
        assertThat(spell.countable()).isFalse();
        assertThat(spell.outcome()).isNotEqualTo(RetrySpell.Outcome.SELF_HEALED);
    }

    @Test
    void aBlindSampleInsideTheSpellVoidsItLikeATruncatedOneTaintsIt() {
        List<SpellSample> samples =
                List.of(sample(0, 4, 0), sample(1, 4, 2), blind(2, 4, 1), sample(3, 4, 0), sample(4, 4, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).gapVoided()).isTrue();
        assertThat(spells.get(0).countable()).isFalse();
    }

    @Test
    void aBlindLookaheadCannotJudgeTheOutcomeEither() {
        // The whole outcome test is "did the DLQ grow by the look-ahead bucket" — a blind
        // look-ahead may be missing the very engine the escalation landed on.
        List<SpellSample> samples = List.of(sample(0, 4, 0), sample(1, 4, 2), sample(2, 4, 0), blind(3, 4, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).gapVoided()).isTrue();
        assertThat(spells.get(0).outcome()).isNotEqualTo(RetrySpell.Outcome.SELF_HEALED);
        assertThat(spells.get(0).countable()).isFalse();
    }

    @Test
    void aCompleteCycleSeriesIsStillJudgedNormally() {
        // Guard against over-voiding: the marker must only bite when a sample was actually blind.
        List<SpellSample> samples = List.of(sample(0, 12, 0), sample(1, 12, 4), sample(2, 12, 0), sample(3, 12, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells.get(0).gapVoided()).isFalse();
        assertThat(spells.get(0).countable()).isTrue();
    }

    /* ---------------- #372 fleet composition: a registry edit is not evidence ---------------- */

    @Test
    void aRegistryDisableThatForgesASpellEndIsVoidedNotMintedAsSelfHealed() {
        // The §16.2 scenario, end to end. The class holds its RETRYING jobs on engine-b and its
        // standing dead-letters on engine-a. An operator DISABLES engine-b: it leaves
        // EngineRegistry.all() entirely, so the pass never fans out to it, never fails to hear
        // from it, and writes a row that is honestly cycle_complete = true, NOT truncated, with
        // NO gap — with retryingCount = 0 because engine-b's retrying members are simply no
        // longer in scope. The edge detector reads the spell as ENDED; the +1 look-ahead compares
        // engine-a's UNCHANGED dead-letter count against spell start, finds no growth, and on the
        // BASE records Outcome.SELF_HEALED, countable, gapVoided=false, truncationTainted=false —
        // fabricated self-heal evidence minted by a routine admin action, invisible to every
        // existing rail. Only the recorded SCOPE can see it.
        List<SpellSample> samples = List.of(
                scoped(0, 12, 0, FLEET),
                scoped(1, 12, 4, FLEET),
                scoped(2, 12, 4, FLEET),
                scoped(3, 12, 0, FLEET_AFTER_DISABLE), // engine-b DISABLED — not "the retries finished"
                scoped(4, 12, 0, FLEET_AFTER_DISABLE));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        RetrySpell spell = spells.get(0);
        assertThat(spell.outcome()).isNotEqualTo(RetrySpell.Outcome.SELF_HEALED);
        assertThat(spell.outcome()).isEqualTo(RetrySpell.Outcome.UNKNOWN);
        assertThat(spell.gapVoided()).isTrue();
        assertThat(spell.excluded()).isTrue();
        assertThat(spell.countable()).isFalse();
        // ...and none of the pre-#372 rails would have caught it: the rows are complete and whole.
        assertThat(spell.truncationTainted()).isFalse();
        assertThat(spell.live()).isFalse();
    }

    @Test
    void aFleetChangeINSIDETheRunVoidsTheSpellToo() {
        // A re-enable mid-spell: the run's own retrying levels are taken over two different
        // engine sets, so its shape (and its dlqAtStart baseline) is not one series.
        List<SpellSample> samples = List.of(
                scoped(0, 4, 0, FLEET_AFTER_DISABLE),
                scoped(1, 4, 2, FLEET_AFTER_DISABLE),
                scoped(2, 4, 3, FLEET), // engine-b re-enabled mid-run
                scoped(3, 4, 0, FLEET),
                scoped(4, 4, 0, FLEET));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).gapVoided()).isTrue();
        assertThat(spells.get(0).countable()).isFalse();
    }

    @Test
    void aLookaheadFromADifferentFleetCannotJudgeTheOutcomeEither() {
        // The run AND its closing zero describe one fleet, so the shape itself is clean — but the
        // outcome test is "did the DLQ GROW by the look-ahead bucket", and a look-ahead taken over
        // a different engine set reports a dead-letter level that is not comparable to spell
        // start. Same disposition as a blind look-ahead: void, never guess.
        List<SpellSample> samples = List.of(
                scoped(0, 4, 0, FLEET),
                scoped(1, 4, 2, FLEET),
                scoped(2, 4, 0, FLEET),
                scoped(3, 4, 0, FLEET_AFTER_DISABLE));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).outcome()).isNotEqualTo(RetrySpell.Outcome.SELF_HEALED);
        assertThat(spells.get(0).gapVoided()).isTrue();
        assertThat(spells.get(0).countable()).isFalse();
    }

    @Test
    void anUNRECORDEDFleetAnywhereInTheShapeVoidsTheSpell() {
        // A pre-V22 row (or any write path that failed to state scope) carries "". It is
        // comparable to NOTHING, itself included — so it can neither anchor a span nor extend one.
        List<SpellSample> samples =
                List.of(scoped(0, 4, 0, FLEET), scoped(1, 4, 2, FLEET), scoped(2, 4, 0, ""), scoped(3, 4, 0, FLEET));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).gapVoided()).isTrue();
        assertThat(spells.get(0).countable()).isFalse();
    }

    @Test
    void aWhollyUnrecordedSeriesIsVoidedRatherThanTreatedAsOneFleet() {
        // Two adjacent "" rows are NOT known to be the same scope, so an all-"" span (exactly what
        // the V22 fail-closed backfill leaves behind) never judges an outcome.
        List<SpellSample> samples =
                List.of(scoped(0, 4, 0, ""), scoped(1, 4, 2, ""), scoped(2, 4, 0, ""), scoped(3, 4, 0, ""));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        assertThat(spells.get(0).outcome()).isEqualTo(RetrySpell.Outcome.UNKNOWN);
        assertThat(spells.get(0).gapVoided()).isTrue();
        assertThat(spells.get(0).countable()).isFalse();
    }

    @Test
    void aConstantRecordedFleetIsStillJudgedNormally() {
        // Guard against over-voiding, the mirror of the #302 guard above: the scope rule must only
        // bite on a composition CHANGE or an unrecorded scope, never on a steady fleet.
        List<SpellSample> samples = List.of(
                scoped(0, 12, 0, FLEET), scoped(1, 12, 4, FLEET), scoped(2, 12, 0, FLEET), scoped(3, 12, 0, FLEET));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells.get(0).gapVoided()).isFalse();
        assertThat(spells.get(0).outcome()).isEqualTo(RetrySpell.Outcome.SELF_HEALED);
        assertThat(spells.get(0).countable()).isTrue();
    }

    /* ---------------- left censoring ---------------- */

    @Test
    void aSpellAlreadyInProgressAtTheFirstSampleIsLeftCensoredAndNeverCounted() {
        // The window (or the row cap) opened MID-spell: dlqAtStart is a mid-spell level, so an
        // escalation in the unobserved prefix reads as a clean heal, and the measured duration
        // is short by that prefix — which would then bias p50/p90 low.
        List<SpellSample> samples = List.of(sample(0, 7, 3), sample(1, 7, 2), sample(2, 7, 0), sample(3, 7, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells).hasSize(1);
        RetrySpell spell = spells.get(0);
        assertThat(spell.leftCensored()).isTrue();
        assertThat(spell.excluded()).isTrue();
        assertThat(spell.countable()).isFalse();
    }

    @Test
    void aSpellWhoseStartWasObservedIsNotCensored() {
        List<SpellSample> samples = List.of(sample(0, 7, 0), sample(1, 7, 3), sample(2, 7, 0), sample(3, 7, 0));

        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, BUCKET, List.of());

        assertThat(spells.get(0).leftCensored()).isFalse();
        assertThat(spells.get(0).countable()).isTrue();
    }

    @Test
    void noRetryingSamplesAtAllProduceNoSpells() {
        List<SpellSample> samples = List.of(sample(0, 4, 0), sample(1, 4, 0), sample(2, 4, 0));

        assertThat(RetrySpellExtractor.extract(samples, BUCKET, List.of())).isEmpty();
    }

    @Test
    void anEmptySeriesProducesNoSpells() {
        assertThat(RetrySpellExtractor.extract(List.of(), BUCKET, List.of())).isEmpty();
    }
}

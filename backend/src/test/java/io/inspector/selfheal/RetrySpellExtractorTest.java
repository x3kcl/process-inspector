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

    private static SpellSample sample(int minute, long dlq, long retrying) {
        return new SpellSample(at(minute), dlq, retrying, false);
    }

    private static SpellSample truncated(int minute, long dlq, long retrying) {
        return new SpellSample(at(minute), dlq, retrying, true);
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

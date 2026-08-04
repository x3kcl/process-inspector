package io.inspector.selfheal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the §3.2 aggregation (n/healed/Wilson/tts/exclusions) over synthetic
 * {@link RetrySpell} fixtures — every scenario the design's honesty rails demand, driven
 * WITHOUT any real self-heal data (none exists — R2-SELFHEAL-BASELINE-2026-08.md).
 */
class SelfHealStatsComputerTest {

    private static final Instant T0 = Instant.parse("2026-08-04T00:00:00Z");
    private static final int FLOOR = 10;

    private static RetrySpell healed(long durationSeconds) {
        Instant start = T0;
        Instant end = start.plusSeconds(durationSeconds);
        return new RetrySpell(
                start,
                end,
                Duration.ofSeconds(durationSeconds),
                RetrySpell.Outcome.SELF_HEALED,
                false,
                false,
                false,
                false,
                false);
    }

    private static RetrySpell escalated() {
        return new RetrySpell(
                T0,
                T0.plusSeconds(60),
                Duration.ofSeconds(60),
                RetrySpell.Outcome.ESCALATED,
                false,
                false,
                false,
                false,
                false);
    }

    private static RetrySpell live() {
        return new RetrySpell(T0, T0, Duration.ZERO, RetrySpell.Outcome.UNKNOWN, false, false, false, true, false);
    }

    private static RetrySpell confounded() {
        return new RetrySpell(
                T0,
                T0.plusSeconds(60),
                Duration.ofSeconds(60),
                RetrySpell.Outcome.SELF_HEALED,
                true,
                false,
                false,
                false,
                false);
    }

    private static RetrySpell gapVoided() {
        return new RetrySpell(
                T0,
                T0.plusSeconds(60),
                Duration.ofSeconds(60),
                RetrySpell.Outcome.UNKNOWN,
                false,
                true,
                false,
                false,
                false);
    }

    private static RetrySpell truncationTaintedEscalated() {
        return new RetrySpell(
                T0,
                T0.plusSeconds(60),
                Duration.ofSeconds(60),
                RetrySpell.Outcome.ESCALATED,
                false,
                false,
                true,
                false,
                false);
    }

    @Test
    void belowTheFloorIsInsufficientHistoryEvenWithAPerfectRecord() {
        List<RetrySpell> spells = List.of(healed(60), healed(120), healed(180));

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.n()).isEqualTo(3);
        assertThat(stats.rawLane()).isEqualTo(SelfHealLane.INSUFFICIENT_HISTORY);
        assertThat(stats.wilsonLow()).isNull();
        assertThat(stats.wilsonHigh()).isNull();
    }

    @Test
    void tenPerfectSpellsAtTheFloorAreLikely() {
        List<RetrySpell> spells = ten(SelfHealStatsComputerTest::healedSpell);

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.n()).isEqualTo(10);
        assertThat(stats.healed()).isEqualTo(10);
        assertThat(stats.rawLane()).isEqualTo(SelfHealLane.SELF_HEAL_LIKELY);
        assertThat(stats.wilsonLow()).isEqualTo(0.722, within(0.001));
    }

    @Test
    void tenEscalationsAtTheFloorAreUnlikely() {
        List<RetrySpell> spells = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            spells.add(escalated());
        }

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.healed()).isZero();
        assertThat(stats.rawLane()).isEqualTo(SelfHealLane.SELF_HEAL_UNLIKELY);
        assertThat(stats.wilsonHigh()).isEqualTo(0.278, within(0.001));
    }

    @Test
    void aMixOfOutcomesAtTheFloorIsMixed() {
        List<RetrySpell> spells = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            spells.add(healedSpell());
        }
        for (int i = 0; i < 5; i++) {
            spells.add(escalated());
        }

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.n()).isEqualTo(10);
        assertThat(stats.healed()).isEqualTo(5);
        assertThat(stats.rawLane()).isEqualTo(SelfHealLane.SELF_HEAL_MIXED);
    }

    @Test
    void liveSpellsNeverContributeToNOrHealed() {
        List<RetrySpell> spells = List.of(live(), live());

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.n()).isZero();
        assertThat(stats.excludedSpells()).isZero(); // live is neither countable nor "excluded"
    }

    @Test
    void confoundedGapVoidedAndTruncatedSpellsAreExcludedButCountedAndNeverDroppedSilently() {
        List<RetrySpell> spells = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            spells.add(healedSpell());
        }
        spells.add(confounded());
        spells.add(gapVoided());
        spells.add(truncationTaintedEscalated());

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.n()).isEqualTo(10); // the 3 tainted spells never inflate n
        assertThat(stats.excludedSpells()).isEqualTo(3);
        assertThat(stats.truncationTainted()).isTrue();
    }

    @Test
    void truncationTaintedFlagIsFalseWhenNoExclusionWasDueToTruncation() {
        List<RetrySpell> spells = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            spells.add(healedSpell());
        }
        spells.add(confounded());

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.excludedSpells()).isEqualTo(1);
        assertThat(stats.truncationTainted()).isFalse();
    }

    @Test
    void timeToSelfHealIsAbsentWhenNoSpellEverSelfHealed() {
        List<RetrySpell> spells = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            spells.add(escalated());
        }

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.ttsP50Seconds()).isNull();
        assertThat(stats.ttsP90Seconds()).isNull();
    }

    @Test
    void timeToSelfHealPercentilesAreComputedOverOnlyTheHealedDurations() {
        List<RetrySpell> spells = new ArrayList<>();
        // 10 healed spells with distinct durations 60..600s, plus 2 escalations (excluded from tts).
        for (int i = 1; i <= 10; i++) {
            spells.add(healed(i * 60L));
        }
        spells.add(escalated());
        spells.add(escalated());

        RawSelfHealStats stats = SelfHealStatsComputer.compute(spells, FLOOR);

        assertThat(stats.n()).isEqualTo(12);
        assertThat(stats.healed()).isEqualTo(10);
        assertThat(stats.ttsP50Seconds()).isNotNull();
        assertThat(stats.ttsP90Seconds()).isNotNull();
        assertThat(stats.ttsP50Seconds()).isLessThanOrEqualTo(stats.ttsP90Seconds());
    }

    private static List<RetrySpell> ten(java.util.function.Supplier<RetrySpell> factory) {
        List<RetrySpell> spells = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            spells.add(factory.get());
        }
        return spells;
    }

    private static RetrySpell healedSpell() {
        return healed(60);
    }
}

package io.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** Rung 1: the create-ahead / drop-behind decisions are pure name + range arithmetic. */
class SnapshotPartitionsTest {

    @Test
    void nameEncodesYearAndMonthZeroPadded() {
        assertThat(SnapshotPartitions.name(YearMonth.of(2026, 7))).isEqualTo("triage_snapshot_y2026m07");
        assertThat(SnapshotPartitions.name(YearMonth.of(2026, 12))).isEqualTo("triage_snapshot_y2026m12");
    }

    @Test
    void boundsAreHalfOpenUtcMonthLiterals() {
        var b = SnapshotPartitions.boundsFor(YearMonth.of(2026, 7));
        assertThat(b.name()).isEqualTo("triage_snapshot_y2026m07");
        assertThat(b.fromInclusive()).isEqualTo("2026-07-01 00:00:00+00");
        assertThat(b.toExclusive()).isEqualTo("2026-08-01 00:00:00+00");
    }

    @Test
    void decemberBoundRollsToNextYear() {
        assertThat(SnapshotPartitions.boundsFor(YearMonth.of(2026, 12)).toExclusive())
                .isEqualTo("2027-01-01 00:00:00+00");
    }

    @Test
    void monthOfRoundTripsAndRejectsNonMonthlyChildren() {
        assertThat(SnapshotPartitions.monthOf("triage_snapshot_y2026m07")).contains(YearMonth.of(2026, 7));
        assertThat(SnapshotPartitions.monthOf("triage_snapshot_default")).isEmpty();
        assertThat(SnapshotPartitions.monthOf("some_other_table")).isEmpty();
        assertThat(SnapshotPartitions.monthOf(null)).isEmpty();
    }

    @Test
    void generalizesToAnyChildPrefix() {
        // the incident_occurrence reuse (R-BAU-10 S1) — same math, different table family
        String prefix = "incident_occurrence_y";
        assertThat(SnapshotPartitions.name(prefix, YearMonth.of(2026, 7))).isEqualTo("incident_occurrence_y2026m07");
        var b = SnapshotPartitions.boundsFor(prefix, YearMonth.of(2026, 12));
        assertThat(b.name()).isEqualTo("incident_occurrence_y2026m12");
        assertThat(b.toExclusive()).isEqualTo("2027-01-01 00:00:00+00");
        assertThat(SnapshotPartitions.monthOf(prefix, "incident_occurrence_y2026m07"))
                .contains(YearMonth.of(2026, 7));
        // one family's children never match another family's prefix — no cross-table drops
        assertThat(SnapshotPartitions.monthOf(prefix, "triage_snapshot_y2026m07"))
                .isEmpty();
        assertThat(SnapshotPartitions.isExpired(prefix, "incident_occurrence_y2026m07", LocalDate.parse("2026-08-01")))
                .isTrue();
        assertThat(SnapshotPartitions.isExpired(prefix, "incident_occurrence_default", LocalDate.parse("2099-01-01")))
                .isFalse();
    }

    @Test
    void expiredOnlyWhenTheWholeMonthIsOnOrBeforeTheCutoff() {
        // July 2026's exclusive upper bound is 2026-08-01. It is expired iff cutoff >= that bound.
        assertThat(SnapshotPartitions.isExpired("triage_snapshot_y2026m07", LocalDate.parse("2026-08-01")))
                .isTrue();
        assertThat(SnapshotPartitions.isExpired("triage_snapshot_y2026m07", LocalDate.parse("2026-07-31")))
                .isFalse();
        // The DEFAULT catch-all is never expired — it must never be dropped.
        assertThat(SnapshotPartitions.isExpired("triage_snapshot_default", LocalDate.parse("2099-01-01")))
                .isFalse();
    }
}

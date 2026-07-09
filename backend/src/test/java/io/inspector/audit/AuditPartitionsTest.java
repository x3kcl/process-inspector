package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** Rung 1: pure naming + bounds arithmetic for the audit monthly partitions (M4-CLOSEOUT §A2). */
class AuditPartitionsTest {

    @Test
    void nameUsesTheUnderscoreAuditConvention() {
        assertThat(AuditPartitions.name(YearMonth.of(2026, 7))).isEqualTo("audit_entry_2026_07");
        // Month is zero-padded so lexical order matches chronological order.
        assertThat(AuditPartitions.name(YearMonth.of(2026, 11))).isEqualTo("audit_entry_2026_11");
    }

    @Test
    void boundsAreHalfOpenUtcMonthRanges() {
        AuditPartitions.Bounds b = AuditPartitions.boundsFor(YearMonth.of(2026, 7));
        assertThat(b.name()).isEqualTo("audit_entry_2026_07");
        assertThat(b.fromInclusive()).isEqualTo("2026-07-01 00:00:00+00");
        assertThat(b.toExclusive()).isEqualTo("2026-08-01 00:00:00+00");
    }

    @Test
    void boundsRollOverYearEnd() {
        AuditPartitions.Bounds b = AuditPartitions.boundsFor(YearMonth.of(2026, 12));
        assertThat(b.fromInclusive()).isEqualTo("2026-12-01 00:00:00+00");
        assertThat(b.toExclusive()).isEqualTo("2027-01-01 00:00:00+00");
    }

    @Test
    void monthOfParsesMonthlyChildrenAndRejectsTheRest() {
        assertThat(AuditPartitions.monthOf("audit_entry_2026_07")).contains(YearMonth.of(2026, 7));
        // The DEFAULT partition is not a monthly child.
        assertThat(AuditPartitions.monthOf("audit_entry_default")).isEmpty();
        // Foreign / malformed names never parse.
        assertThat(AuditPartitions.monthOf("triage_snapshot_y2026m07")).isEmpty();
        assertThat(AuditPartitions.monthOf("audit_entry_2026")).isEmpty();
        assertThat(AuditPartitions.monthOf("audit_entry_2026_13")).isEmpty();
        assertThat(AuditPartitions.monthOf(null)).isEmpty();
    }

    @Test
    void nameAndMonthOfRoundTrip() {
        YearMonth m = YearMonth.of(2027, 3);
        assertThat(AuditPartitions.monthOf(AuditPartitions.name(m))).contains(m);
    }

    @Test
    void isExpiredOnlyWhenTheWholeMonthPredatesTheCutoff() {
        // audit_entry_2026_07 covers [2026-07-01, 2026-08-01): expired iff cutoff ≥ 2026-08-01.
        assertThat(AuditPartitions.isExpired("audit_entry_2026_07", java.time.LocalDate.parse("2026-08-01")))
                .isTrue();
        assertThat(AuditPartitions.isExpired("audit_entry_2026_07", java.time.LocalDate.parse("2026-07-31")))
                .isFalse();
    }

    @Test
    void theDefaultPartitionIsNeverExpired() {
        assertThat(AuditPartitions.isExpired("audit_entry_default", java.time.LocalDate.parse("2999-01-01")))
                .isFalse();
    }
}

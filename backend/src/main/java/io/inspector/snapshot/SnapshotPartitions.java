package io.inspector.snapshot;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Pure naming + range arithmetic for monthly range-partitions, kept side-effect-free so the
 * create-ahead / drop-behind decisions are rung-1 testable; the maintainers (via {@link
 * MonthlyPartitionMaintenance}) are the only thing that runs DDL.
 *
 * <p>Born for {@code triage_snapshot} (V5__triage_snapshot.sql) — the no-prefix overloads keep
 * that call surface — and generalized by a {@code childPrefix} parameter for every further
 * monthly-partitioned table ({@code incident_occurrence}, V18: prefix
 * {@code incident_occurrence_y}) rather than copy-pasting the math (R-BAU-10 slice 1).
 */
public final class SnapshotPartitions {

    /** e.g. {@code triage_snapshot_y2026m07} — the parent's monthly child for July 2026. */
    static final String PREFIX = "triage_snapshot_y";

    private SnapshotPartitions() {}

    /** The child partition name for a month. */
    public static String name(YearMonth month) {
        return name(PREFIX, month);
    }

    /** The child partition name for a month under an arbitrary {@code childPrefix}. */
    public static String name(String childPrefix, YearMonth month) {
        return String.format("%s%04dm%02d", childPrefix, month.getYear(), month.getMonthValue());
    }

    /**
     * The half-open range [from, to) for a month, as UTC timestamptz literals — inclusive lower,
     * exclusive upper, matching Postgres RANGE partition bounds.
     */
    public static Bounds boundsFor(YearMonth month) {
        return boundsFor(PREFIX, month);
    }

    /** {@link #boundsFor(YearMonth)} under an arbitrary {@code childPrefix}. */
    public static Bounds boundsFor(String childPrefix, YearMonth month) {
        return new Bounds(
                name(childPrefix, month),
                month.atDay(1) + " 00:00:00+00",
                month.plusMonths(1).atDay(1) + " 00:00:00+00");
    }

    /** The month a child partition name encodes, or empty if it is not a monthly child (e.g. the DEFAULT). */
    public static Optional<YearMonth> monthOf(String partitionName) {
        return monthOf(PREFIX, partitionName);
    }

    /** {@link #monthOf(String)} under an arbitrary {@code childPrefix}. */
    public static Optional<YearMonth> monthOf(String childPrefix, String partitionName) {
        if (partitionName == null || !partitionName.startsWith(childPrefix)) {
            return Optional.empty();
        }
        String tail = partitionName.substring(childPrefix.length()); // "2026m07"
        int m = tail.indexOf('m');
        if (m < 0) {
            return Optional.empty();
        }
        try {
            int year = Integer.parseInt(tail.substring(0, m));
            int mon = Integer.parseInt(tail.substring(m + 1));
            return Optional.of(YearMonth.of(year, mon));
        } catch (NumberFormatException | DateTimeException e) {
            return Optional.empty();
        }
    }

    /**
     * True when every instant a partition could hold is on or before {@code cutoff} — i.e. the
     * whole month has aged past the retention horizon and the partition can be DROPPED (never
     * DELETEd). The exclusive upper bound (first day of the next month) must be ≤ cutoff.
     */
    public static boolean isExpired(String partitionName, LocalDate cutoff) {
        return isExpired(PREFIX, partitionName, cutoff);
    }

    /** {@link #isExpired(String, LocalDate)} under an arbitrary {@code childPrefix}. */
    public static boolean isExpired(String childPrefix, String partitionName, LocalDate cutoff) {
        return monthOf(childPrefix, partitionName)
                .map(month -> !month.plusMonths(1).atDay(1).isAfter(cutoff))
                .orElse(false);
    }

    public record Bounds(String name, String fromInclusive, String toExclusive) {}
}

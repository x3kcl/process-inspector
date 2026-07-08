package io.inspector.snapshot;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Pure naming + range arithmetic for the monthly range-partitions of {@code triage_snapshot}
 * (V5__triage_snapshot.sql). Kept side-effect-free so the create-ahead / drop-behind decisions
 * are rung-1 testable; {@link SnapshotPartitionMaintainer} is the only thing that runs DDL.
 */
public final class SnapshotPartitions {

    /** e.g. {@code triage_snapshot_y2026m07} — the parent's monthly child for July 2026. */
    static final String PREFIX = "triage_snapshot_y";

    private SnapshotPartitions() {}

    /** The child partition name for a month. */
    public static String name(YearMonth month) {
        return String.format("%s%04dm%02d", PREFIX, month.getYear(), month.getMonthValue());
    }

    /**
     * The half-open range [from, to) for a month, as UTC timestamptz literals — inclusive lower,
     * exclusive upper, matching Postgres RANGE partition bounds.
     */
    public static Bounds boundsFor(YearMonth month) {
        return new Bounds(
                name(month),
                month.atDay(1) + " 00:00:00+00",
                month.plusMonths(1).atDay(1) + " 00:00:00+00");
    }

    /** The month a child partition name encodes, or empty if it is not a monthly child (e.g. the DEFAULT). */
    public static Optional<YearMonth> monthOf(String partitionName) {
        if (partitionName == null || !partitionName.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String tail = partitionName.substring(PREFIX.length()); // "2026m07"
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
        return monthOf(partitionName)
                .map(month -> !month.plusMonths(1).atDay(1).isAfter(cutoff))
                .orElse(false);
    }

    public record Bounds(String name, String fromInclusive, String toExclusive) {}
}

package io.inspector.audit;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Pure naming + range arithmetic for the monthly range-partitions of {@code audit_entry}
 * (V1 parent, carved into months by V10). Side-effect-free so the create-ahead decisions are
 * rung-1 testable; {@link AuditPartitionMaintainer} is the only thing that runs DDL.
 *
 * <p>Names follow the audit domain's established convention {@code audit_entry_YYYY_MM} (the
 * underscore date used by {@code deploy/sql/audit-roles.sql} and {@code AuditRoleGrantsIT}), NOT
 * the snapshot side's {@code y2026m07} shape — so the V10 carve, this helper, and the grant model
 * all read the same. The {@code DEFAULT} partition ({@code audit_entry_default}) is not a monthly
 * child and is deliberately unparseable here.
 */
public final class AuditPartitions {

    /** e.g. {@code audit_entry_2026_07} — the parent's monthly child for July 2026. */
    static final String PREFIX = "audit_entry_";

    private AuditPartitions() {}

    /** The child partition name for a month. */
    public static String name(YearMonth month) {
        return String.format("%s%04d_%02d", PREFIX, month.getYear(), month.getMonthValue());
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

    /**
     * The month a child partition name encodes, or empty if it is not a monthly child — notably
     * the DEFAULT partition ({@code audit_entry_default}), whose tail {@code "default"} does not
     * parse.
     */
    public static Optional<YearMonth> monthOf(String partitionName) {
        if (partitionName == null || !partitionName.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String tail = partitionName.substring(PREFIX.length()); // "2026_07"
        int sep = tail.indexOf('_');
        if (sep < 0) {
            return Optional.empty();
        }
        try {
            int year = Integer.parseInt(tail.substring(0, sep));
            int month = Integer.parseInt(tail.substring(sep + 1));
            return Optional.of(YearMonth.of(year, month));
        } catch (NumberFormatException | DateTimeException e) {
            return Optional.empty();
        }
    }

    /**
     * True when every instant a partition could hold is on or before {@code cutoff} — i.e. the whole
     * month has aged past the retention horizon and is a candidate for the S5b purge (the DROP itself
     * goes through {@code purge_audit()}, which re-checks age + legal-hold in the DB). The exclusive
     * upper bound (first day of the next month) must be ≤ cutoff. The DEFAULT partition never
     * qualifies ({@link #monthOf} returns empty for it).
     */
    public static boolean isExpired(String partitionName, LocalDate cutoff) {
        return monthOf(partitionName)
                .map(month -> !month.plusMonths(1).atDay(1).isAfter(cutoff))
                .orElse(false);
    }

    public record Bounds(String name, String fromInclusive, String toExclusive) {}
}

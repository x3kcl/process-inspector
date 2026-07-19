package io.inspector.snapshot;

import io.inspector.snapshot.SnapshotPartitions.Bounds;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shared create-ahead / drop-behind engine for a monthly range-partitioned table with a
 * DEFAULT catch-all — extracted from the original {@code triage_snapshot}-only maintainer so
 * {@code incident_occurrence} (V18, R-BAU-10) reuses the mechanism instead of copy-pasting it.
 * Retention is DROP-PARTITION, never DELETE — a monthly DROP never locks the active partition
 * being written.
 *
 * <ul>
 *   <li><b>Create-ahead</b> the current + next month so a row never lands in the DEFAULT
 *       safety-net partition (a row already sitting in DEFAULT would block the later CREATE —
 *       so the owning maintainer runs BEFORE the first write, ordered ahead of it at startup).</li>
 *   <li><b>Drop-behind</b> any monthly partition whose whole range has aged past the retention
 *       horizon. The DEFAULT partition is never dropped.</li>
 * </ul>
 *
 * The store being unavailable degrades to a skipped run (one warn), never a failure. Not a
 * bean: each table's maintainer composes one with its own parent/prefix/flag/retention.
 */
public final class MonthlyPartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(MonthlyPartitionMaintenance.class);

    private final JdbcTemplate jdbc;
    private final String parent;
    private final String childPrefix;

    public MonthlyPartitionMaintenance(JdbcTemplate jdbc, String parent, String childPrefix) {
        this.jdbc = jdbc;
        this.parent = parent;
        this.childPrefix = childPrefix;
    }

    public void maintain(Instant now, int retentionDays) {
        try {
            YearMonth thisMonth = YearMonth.from(LocalDate.ofInstant(now, ZoneOffset.UTC));
            ensurePartition(thisMonth);
            ensurePartition(thisMonth.plusMonths(1));
            dropExpired(now, retentionDays);
        } catch (RuntimeException e) {
            log.warn("{} partition maintenance skipped — store unavailable: {}", parent, e.toString());
        }
    }

    private void ensurePartition(YearMonth month) {
        Bounds b = SnapshotPartitions.boundsFor(childPrefix, month);
        try {
            // Identifiers are code-controlled (never user input); bounds are UTC literal strings.
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + b.name() + " PARTITION OF " + parent + " FOR VALUES FROM ('"
                    + b.fromInclusive() + "') TO ('" + b.toExclusive() + "')");
        } catch (RuntimeException e) {
            // Most likely the DEFAULT partition already holds a row in this month's range (a
            // deployment that ran before this maintainer existed). The rows stay queryable in
            // DEFAULT — just not individually droppable. Logged, never silent.
            log.warn(
                    "{} partition {} not created (rows may already sit in DEFAULT): {}",
                    parent,
                    b.name(),
                    e.toString());
        }
    }

    private void dropExpired(Instant now, int retentionDays) {
        LocalDate cutoff = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(retentionDays);
        List<String> children = jdbc.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ?
                """, String.class, parent);
        for (String child : children) {
            if (SnapshotPartitions.isExpired(childPrefix, child, cutoff)) {
                jdbc.execute("DROP TABLE IF EXISTS " + child);
                log.info("{}: dropped expired partition {} (retention cutoff {})", parent, child, cutoff);
            }
        }
    }
}

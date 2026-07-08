package io.inspector.snapshot;

import io.inspector.config.InspectorProperties;
import io.inspector.snapshot.SnapshotPartitions.Bounds;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Range-partition housekeeping for {@code triage_snapshot} (PLAN v2/M4 retention: 400-day
 * revFADP, R-BAU-08). Retention is DROP-PARTITION, never DELETE — a monthly DROP never locks the
 * active partition the sampler is writing.
 *
 * <ul>
 *   <li><b>Create-ahead</b> the current + next month so a row never lands in the DEFAULT
 *       safety-net partition (a row already sitting in DEFAULT would block the later CREATE —
 *       so we create BEFORE the sampler's first write, ordered ahead of it at startup).</li>
 *   <li><b>Drop-behind</b> any monthly partition whose whole range has aged past the retention
 *       horizon. The DEFAULT partition is never dropped.</li>
 * </ul>
 *
 * The store being unavailable degrades to a skipped run (one warn), never a failure.
 */
@Component
@ConditionalOnProperty(name = "inspector.snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class SnapshotPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPartitionMaintainer.class);
    private static final String PARENT = "triage_snapshot";

    private final JdbcTemplate jdbc;
    private final InspectorProperties properties;
    private final Clock clock;

    public SnapshotPartitionMaintainer(JdbcTemplate jdbc, InspectorProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** Ordered ahead of {@link SnapshotSampler#sampleOnStartup} so partitions exist before the first write. */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void maintainOnStartup() {
        maintain();
    }

    /** Daily — create-ahead crosses month boundaries; drop-behind reclaims expired months. */
    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT24H")
    public void maintainPeriodically() {
        maintain();
    }

    void maintain() {
        try {
            YearMonth thisMonth = YearMonth.from(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
            ensurePartition(thisMonth);
            ensurePartition(thisMonth.plusMonths(1));
            dropExpired();
        } catch (RuntimeException e) {
            log.warn("snapshot partition maintenance skipped — store unavailable: {}", e.toString());
        }
    }

    private void ensurePartition(YearMonth month) {
        Bounds b = SnapshotPartitions.boundsFor(month);
        try {
            // Identifiers are code-controlled (never user input); bounds are UTC literal strings.
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + b.name() + " PARTITION OF " + PARENT + " FOR VALUES FROM ('"
                    + b.fromInclusive() + "') TO ('" + b.toExclusive() + "')");
        } catch (RuntimeException e) {
            // Most likely the DEFAULT partition already holds a row in this month's range (a
            // deployment that ran before this maintainer existed). The rows stay queryable in
            // DEFAULT — just not individually droppable. Logged, never silent.
            log.warn("snapshot partition {} not created (rows may already sit in DEFAULT): {}", b.name(), e.toString());
        }
    }

    private void dropExpired() {
        LocalDate cutoff = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusDays(properties.snapshotOrDefault().retentionDaysOrDefault());
        List<String> children = jdbc.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ?
                """, String.class, PARENT);
        for (String child : children) {
            if (SnapshotPartitions.isExpired(child, cutoff)) {
                jdbc.execute("DROP TABLE IF EXISTS " + child);
                log.info("snapshot: dropped expired partition {} (retention cutoff {})", child, cutoff);
            }
        }
    }
}

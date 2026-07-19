package io.inspector.snapshot;

import io.inspector.config.InspectorProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Range-partition housekeeping for {@code triage_snapshot} (PLAN v2/M4 retention: 400-day
 * revFADP, R-BAU-08): create-ahead current+next month, drop-behind past the retention
 * horizon — the shared {@link MonthlyPartitionMaintenance} mechanism, which this class
 * scopes to the snapshot table, flag and retention knob ({@code incident_occurrence} has its
 * own maintainer in {@code io.inspector.incident}, same mechanism).
 */
@Component
@ConditionalOnProperty(name = "inspector.snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class SnapshotPartitionMaintainer {

    private final MonthlyPartitionMaintenance maintenance;
    private final InspectorProperties properties;
    private final Clock clock;

    public SnapshotPartitionMaintainer(JdbcTemplate jdbc, InspectorProperties properties, Clock clock) {
        this.maintenance = new MonthlyPartitionMaintenance(jdbc, "triage_snapshot", SnapshotPartitions.PREFIX);
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
        maintenance.maintain(clock.instant(), properties.snapshotOrDefault().retentionDaysOrDefault());
    }
}

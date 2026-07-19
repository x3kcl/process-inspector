package io.inspector.incident;

import io.inspector.config.InspectorProperties;
import io.inspector.snapshot.MonthlyPartitionMaintenance;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Range-partition housekeeping for {@code incident_occurrence} (V18, INCIDENT-LEDGER.md §3.3):
 * the {@code SnapshotPartitionMaintainer} mechanism explicitly extended to the ledger's
 * time-series — create-ahead current+next month while EMPTY (zero-cost FK validation), drop
 * whole months past the {@code inspector.incidents.retention-days} horizon (400-day revFADP
 * default, aligned with the snapshot store). A missed rotation lands rows in the DEFAULT
 * catch-all, never fails an insert. Gated by the ledger's OWN flag — the two stores'
 * maintainers switch independently, like their consumers.
 */
@Component
@ConditionalOnProperty(name = "inspector.incidents.enabled", havingValue = "true", matchIfMissing = true)
public class IncidentOccurrencePartitionMaintainer {

    /** e.g. {@code incident_occurrence_y2026m07} — the monthly child for July 2026. */
    static final String CHILD_PREFIX = "incident_occurrence_y";

    private final MonthlyPartitionMaintenance maintenance;
    private final InspectorProperties properties;
    private final Clock clock;

    public IncidentOccurrencePartitionMaintainer(JdbcTemplate jdbc, InspectorProperties properties, Clock clock) {
        this.maintenance = new MonthlyPartitionMaintenance(jdbc, "incident_occurrence", CHILD_PREFIX);
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Ordered ahead of {@code SnapshotSampler#sampleOnStartup} (which publishes the event the
     * ledger ingests from) so partitions exist before the first occurrence write.
     */
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
        maintenance.maintain(clock.instant(), properties.incidentsOrDefault().retentionDaysOrDefault());
    }
}

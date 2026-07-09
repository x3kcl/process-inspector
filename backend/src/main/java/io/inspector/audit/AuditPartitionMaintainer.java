package io.inspector.audit;

import io.inspector.audit.AuditPartitions.Bounds;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
 * Create-ahead monthly range-partitions for the {@code audit_entry} golden master (M4-CLOSEOUT
 * §A2 / S5a). The prerequisite for retention: without monthly partitions there is nothing for the
 * S5b purge to drop, and rows pile in the {@code audit_entry_default} safety net.
 *
 * <ul>
 *   <li><b>Create-ahead</b> the current + next month so an audit row never lands in DEFAULT. V10
 *       carved the existing default rows into months and created the first ahead-partitions; this
 *       keeps them rolling forward as time advances.</li>
 *   <li><b>Guard alert</b> — after each run, if {@code audit_entry_default} holds any row, emit the
 *       stable {@code AUDIT_DEFAULT_PARTITION_NONEMPTY} ERROR marker. A non-empty default means
 *       create-ahead is failing (or a row arrived for an uncreated month), so those rows are
 *       <b>un-droppable</b> by the S5b purge — a silent no-op the marker turns loud.</li>
 * </ul>
 *
 * <p>This mirrors {@link io.inspector.snapshot.SnapshotPartitionMaintainer}, with one scope
 * difference: it does <b>not</b> drop expired partitions. Audit retention is deliberately NOT an
 * app-role {@code DROP} — it runs through the S5b {@code SECURITY DEFINER purge_audit()} under a
 * dedicated ops role (age + legal-hold enforced in the DB). Likewise the {@code CREATE} here runs
 * as the schema owner (every current deployment connects as owner); when a prod deployment flips
 * the BFF to the non-owner {@code inspector_app} role, this DDL is refused and the guard alert
 * above fires — the create-ahead then moves behind a {@code SECURITY DEFINER} helper alongside
 * S5b's purge (deploy/sql/audit-roles.sql already anticipates this). Store unavailable → a skipped
 * run (one warn), never a failure.
 */
@Component
@ConditionalOnProperty(
        name = "inspector.audit.partition-maintenance.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AuditPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionMaintainer.class);
    private static final String PARENT = "audit_entry";
    private static final String DEFAULT_PARTITION = "audit_entry_default";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditPartitionMaintainer(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Ordered early at startup so partitions exist before the first mutation writes an audit row. */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void maintainOnStartup() {
        maintain();
    }

    /** Daily — create-ahead crosses month boundaries as time advances. */
    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT24H")
    public void maintainPeriodically() {
        maintain();
    }

    void maintain() {
        try {
            YearMonth thisMonth = YearMonth.from(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
            ensurePartition(thisMonth);
            ensurePartition(thisMonth.plusMonths(1));
            alertIfDefaultNonEmpty();
        } catch (RuntimeException e) {
            log.warn("audit partition maintenance skipped — store unavailable: {}", e.toString());
        }
    }

    private void ensurePartition(YearMonth month) {
        Bounds b = AuditPartitions.boundsFor(month);
        try {
            // Identifiers are code-controlled (never user input); bounds are UTC literal strings.
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + b.name() + " PARTITION OF " + PARENT + " FOR VALUES FROM ('"
                    + b.fromInclusive() + "') TO ('" + b.toExclusive() + "')");
        } catch (RuntimeException e) {
            // Either the DEFAULT already holds a row in this month's range (a deployment that ran
            // before this maintainer existed — carved by V10, but a race is possible), or the
            // connection is the non-owner app role that cannot run owner DDL. Rows stay queryable
            // in DEFAULT; the guard alert below makes the un-droppable backlog loud, never silent.
            log.warn(
                    "audit partition {} not created (rows may sit in DEFAULT, or the connection lacks owner DDL): {}",
                    b.name(),
                    e.toString());
        }
    }

    /**
     * The create-ahead health signal (M4-CLOSEOUT §A2, ops observability). No metrics stack exists
     * yet, so — like {@code AUDIT_CONFIG_EVENT_FAILURE} — the alert substrate is a stable, greppable
     * ERROR marker for log-based alerting.
     */
    private void alertIfDefaultNonEmpty() {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM " + DEFAULT_PARTITION, Long.class);
        if (rows != null && rows > 0) {
            log.error(
                    "AUDIT_DEFAULT_PARTITION_NONEMPTY default_partition_rows={} — audit rows are landing in the"
                            + " DEFAULT partition; monthly create-ahead is failing or a partition is missing, so the"
                            + " retention purge cannot drop them (M4-CLOSEOUT §A2).",
                    rows);
        }
    }
}

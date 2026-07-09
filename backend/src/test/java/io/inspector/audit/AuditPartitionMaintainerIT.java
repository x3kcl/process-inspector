package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@link AuditPartitionMaintainer} against a real Postgres (M4-CLOSEOUT §A2 / S5a): it
 * create-aheads future months, and raises the {@code AUDIT_DEFAULT_PARTITION_NONEMPTY} marker when
 * a row lands in the DEFAULT partition. Owner connection, no Spring context. Local-only (not in
 * ci.yml's itClass), like {@link AuditRoleGrantsIT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditPartitionMaintainerIT {

    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;

    private ListAppender<ILoggingEvent> logs;
    private Logger maintainerLogger;

    @BeforeAll
    void provision() {
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource ds =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
    }

    @AfterEach
    void detachLogs() {
        if (maintainerLogger != null && logs != null) {
            maintainerLogger.detachAppender(logs);
        }
        // Keep tests independent: empty the DEFAULT safety net (owner TRUNCATE is not the
        // append-only guard's concern — it is BEFORE UPDATE OR DELETE, not TRUNCATE).
        jdbc.execute("TRUNCATE audit_entry_default");
    }

    private AuditPartitionMaintainer maintainerAt(String instant) {
        return new AuditPartitionMaintainer(jdbc, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private boolean partitionExists(String name) {
        Boolean present = jdbc.queryForObject("SELECT to_regclass('" + name + "') IS NOT NULL", Boolean.class);
        return Boolean.TRUE.equals(present);
    }

    @Test
    void createsAheadTheCurrentAndNextMonthForItsClock() {
        // A future month with no partition yet — the maintainer must create it and its successor.
        assertThat(partitionExists("audit_entry_2027_06")).isFalse();

        maintainerAt("2027-06-15T00:00:00Z").maintain();

        assertThat(partitionExists("audit_entry_2027_06")).isTrue();
        assertThat(partitionExists("audit_entry_2027_07")).isTrue();
    }

    @Test
    void raisesTheMarkerWhenARowLandsInDefault() {
        // A row for a month with no partition (far future) falls to the DEFAULT safety net.
        jdbc.update("INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome)"
                + " VALUES (gen_random_uuid(), 'c', 'system', TIMESTAMPTZ '2099-06-01 00:00:00+00',"
                + " '_inspector', 'test', 'PENDING')");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry_default", Long.class))
                .isEqualTo(1L);

        attachLogs();
        maintainerAt("2026-07-15T00:00:00Z").maintain();

        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage())
                    .contains("AUDIT_DEFAULT_PARTITION_NONEMPTY")
                    .contains("default_partition_rows=1");
        });
    }

    @Test
    void isQuietWhenDefaultIsEmpty() {
        attachLogs();
        maintainerAt("2026-07-15T00:00:00Z").maintain();

        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("AUDIT_DEFAULT_PARTITION_NONEMPTY"));
    }

    private void attachLogs() {
        logs = new ListAppender<>();
        logs.start();
        maintainerLogger = (Logger) LoggerFactory.getLogger(AuditPartitionMaintainer.class);
        maintainerLogger.addAppender(logs);
    }
}

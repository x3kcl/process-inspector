package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rung 1 for the audit partition maintainer: it create-aheads the current + next month and raises
 * the stable {@code AUDIT_DEFAULT_PARTITION_NONEMPTY} marker iff the DEFAULT partition holds rows.
 * DDL against a real Postgres is {@code AuditPartitionMaintainerIT}.
 */
class AuditPartitionMaintainerTest {

    // Pinned mid-month so "this month" + "next month" are unambiguous.
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private AuditPartitionMaintainer maintainer;

    private ListAppender<ILoggingEvent> logs;
    private Logger maintainerLogger;

    @BeforeEach
    void setUp() {
        maintainer = new AuditPartitionMaintainer(jdbc, clock);
        logs = new ListAppender<>();
        logs.start();
        maintainerLogger = (Logger) LoggerFactory.getLogger(AuditPartitionMaintainer.class);
        maintainerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        maintainerLogger.detachAppender(logs);
    }

    @Test
    void createsCurrentAndNextMonthPartitionsAhead() {
        when(jdbc.queryForObject(eq("SELECT count(*) FROM audit_entry_default"), eq(Long.class)))
                .thenReturn(0L);

        maintainer.maintain();

        verify(jdbc)
                .execute("CREATE TABLE IF NOT EXISTS audit_entry_2026_07 PARTITION OF audit_entry"
                        + " FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00')");
        verify(jdbc)
                .execute("CREATE TABLE IF NOT EXISTS audit_entry_2026_08 PARTITION OF audit_entry"
                        + " FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00')");
    }

    @Test
    void doesNotAlertWhenDefaultPartitionIsEmpty() {
        when(jdbc.queryForObject(eq("SELECT count(*) FROM audit_entry_default"), eq(Long.class)))
                .thenReturn(0L);

        maintainer.maintain();

        assertThat(hasErrorMarker()).isFalse();
    }

    @Test
    void raisesTheStableMarkerWhenDefaultPartitionHoldsRows() {
        when(jdbc.queryForObject(eq("SELECT count(*) FROM audit_entry_default"), eq(Long.class)))
                .thenReturn(7L);

        maintainer.maintain();

        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage())
                    .contains("AUDIT_DEFAULT_PARTITION_NONEMPTY")
                    .contains("default_partition_rows=7");
        });
    }

    @Test
    void aFailedCreateIsSwallowedAndStillReachesTheGuard() {
        // The non-owner app role (or a DEFAULT-overlap race) can refuse the CREATE — maintenance
        // must not throw, and the guard must still run so the backlog is surfaced.
        when(jdbc.queryForObject(eq("SELECT count(*) FROM audit_entry_default"), eq(Long.class)))
                .thenReturn(3L);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("no owner DDL"))
                .when(jdbc)
                .execute(org.mockito.ArgumentMatchers.anyString());

        maintainer.maintain(); // must not throw

        assertThat(hasErrorMarker()).isTrue();
    }

    private boolean hasErrorMarker() {
        return logs.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("AUDIT_DEFAULT_PARTITION_NONEMPTY"));
    }
}

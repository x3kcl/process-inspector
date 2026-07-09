package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the {@code purge_audit()} SECURITY DEFINER function (V11, M4-CLOSEOUT §A2 / S5b) enforces,
 * IN THE DB, that a partition may be dropped only when it is (a) older than the caller's cutoff,
 * (b) the cutoff is not newer than the hard retention floor, (c) no active legal hold overlaps it —
 * and never the DEFAULT partition. Pure Postgres/JDBC + Flyway, owner connection. Local-only (not
 * in ci.yml's itClass), like {@link AuditRoleGrantsIT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditRetentionPurgeIT {

    // A cutoff safely OLDER than the 400-day floor, so the floor check passes and old partitions
    // (2019) are entirely aged out.
    private static final String AGED_CUTOFF = "now() - interval '450 days'";

    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    void provision() throws Exception {
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            // Two ancient monthly partitions (well past any floor) + one row each.
            createMonth(st, "audit_entry_2019_06", "2019-06-01", "2019-07-01");
            createMonth(st, "audit_entry_2019_07", "2019-07-01", "2019-08-01");
            createMonth(st, "audit_entry_2019_08", "2019-08-01", "2019-09-01");
            seed(st, "2019-06-15 09:00:00+00");
            seed(st, "2019-07-15 09:00:00+00");
            seed(st, "2019-08-15 09:00:00+00");
        }
    }

    private static void createMonth(Statement st, String name, String from, String to) throws SQLException {
        st.executeUpdate("CREATE TABLE " + name + " PARTITION OF audit_entry FOR VALUES FROM ('" + from + "') TO ('"
                + to + "')");
    }

    private static void seed(Statement st, String ts) throws SQLException {
        st.executeUpdate("INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome)"
                + " VALUES (gen_random_uuid(), 'c', 'system', '" + ts + "', 'engine-a', 'retry-job', 'ok')");
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static boolean exists(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT to_regclass('" + table + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    @Test
    void refusesACutoffNewerThanTheRetentionFloor() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            // now() is well within the 400-day floor → refused before anything is inspected.
            assertThatThrownBy(() -> st.executeQuery("SELECT purge_audit('audit_entry_2019_06', now())"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("retention floor");
            assertThat(exists(st, "audit_entry_2019_06")).isTrue();
        }
    }

    @Test
    void refusesAPartitionNotEntirelyOlderThanTheCutoff() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            // 2019_08 [..2019-09-01) with a cutoff of 2019-08-15 → the partition straddles the cutoff.
            assertThatThrownBy(() -> st.executeQuery(
                            "SELECT purge_audit('audit_entry_2019_08', TIMESTAMPTZ '2019-08-15 00:00:00+00')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("not entirely older");
            assertThat(exists(st, "audit_entry_2019_08")).isTrue();
        }
    }

    @Test
    void refusesADefaultPartition() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("SELECT purge_audit('audit_entry_default', " + AGED_CUTOFF + ")"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("DEFAULT partition");
            assertThat(exists(st, "audit_entry_default")).isTrue();
        }
    }

    @Test
    void refusesANonChildTable() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("SELECT purge_audit('legal_hold', " + AGED_CUTOFF + ")"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("not a monthly partition");
            assertThat(exists(st, "legal_hold")).isTrue();
        }
    }

    @Test
    void refusesWhenAnActiveLegalHoldOverlaps() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO legal_hold (id, from_ts, to_ts, reason, created_by, created_at)"
                    + " VALUES (gen_random_uuid(), '2019-07-10 00:00:00+00', '2019-07-20 00:00:00+00',"
                    + " 'case 4711 preservation', 'alice', now())");

            assertThatThrownBy(() -> st.executeQuery("SELECT purge_audit('audit_entry_2019_07', " + AGED_CUTOFF + ")"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("legal hold");
            assertThat(exists(st, "audit_entry_2019_07")).isTrue();

            // A RELEASED hold no longer blocks.
            st.executeUpdate("UPDATE legal_hold SET released_at = now(), released_by = 'alice'");
            try (ResultSet rs = st.executeQuery(
                    "SELECT dropped_partition FROM purge_audit('audit_entry_2019_07', " + AGED_CUTOFF + ")")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("audit_entry_2019_07");
            }
            assertThat(exists(st, "audit_entry_2019_07")).isFalse();
        }
    }

    @Test
    void dropsAnAgedUnheldPartitionAndReturnsItsRange() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            assertThat(exists(st, "audit_entry_2019_06")).isTrue();
            try (ResultSet rs = st.executeQuery(
                    "SELECT dropped_partition, range_from, range_to FROM purge_audit('audit_entry_2019_06', "
                            + AGED_CUTOFF + ")")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("dropped_partition")).isEqualTo("audit_entry_2019_06");
                assertThat(rs.getString("range_from")).startsWith("2019-06-01");
                assertThat(rs.getString("range_to")).startsWith("2019-07-01");
            }
            assertThat(exists(st, "audit_entry_2019_06")).isFalse();
        }
    }
}

package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Proves the V10 carve (M4-CLOSEOUT §A2 / S5a) moves rows that predate monthly partitioning out of
 * {@code audit_entry_default} into monthly children — WITHOUT relaxing append-only and WITHOUT
 * disturbing the tamper-evidence chain. Simulates an existing deployment: migrate to V9 (default
 * only), seed rows straight into DEFAULT, then migrate V10 and assert the carve.
 *
 * <p>Pure Postgres/JDBC + Flyway — no Spring, no Flowable. Owner connection (the container
 * superuser stands in for {@code inspector}), mirroring Flyway-as-owner. Local-only (not in
 * ci.yml's itClass), like {@link AuditRoleGrantsIT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditPartitionCarveIT {

    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    void provision() throws Exception {
        postgres.start();

        // 1. Migrate to V9 — only audit_entry_default exists (pre-partitioning deployment).
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();

        // 2. Seed rows straight into DEFAULT, spanning March + May 2026 (April deliberately skipped,
        //    to prove the carve creates the whole contiguous span, not just months with data).
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            st.executeUpdate(seedRow("2026-03-10 09:00:00+00", "hash-march"));
            st.executeUpdate(seedRow("2026-05-20 14:30:00+00", "hash-may-1"));
            st.executeUpdate(seedRow("2026-05-25 08:00:00+00", "hash-may-2"));
            // Everything is in DEFAULT before the carve.
            assertThat(count(st, "audit_entry_default")).isEqualTo(3);
        }

        // 3. Migrate V10 — the carve.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("10")
                .load()
                .migrate();
    }

    private static String seedRow(String ts, String chainHash) {
        return "INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome, chain_hash)"
                + " VALUES (gen_random_uuid(), 'c', 'alice', '" + ts + "', 'engine-a', 'retry-job', 'ok', '"
                + chainHash + "')";
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void defaultPartitionIsEmptiedByTheCarve() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            assertThat(count(st, "audit_entry_default")).isZero();
        }
    }

    @Test
    void theContiguousMonthSpanIsCreatedIncludingTheGapMonth() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            assertThat(exists(st, "audit_entry_2026_03")).isTrue();
            assertThat(exists(st, "audit_entry_2026_04"))
                    .as("gap month created too")
                    .isTrue();
            assertThat(exists(st, "audit_entry_2026_05")).isTrue();
        }
    }

    @Test
    void rowsLandInTheirCorrectMonthlyChildWithChainPreserved() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            // Routed by ts into the right child…
            assertThat(count(st, "audit_entry_2026_03")).isEqualTo(1);
            assertThat(count(st, "audit_entry_2026_05")).isEqualTo(2);
            assertThat(count(st, "audit_entry_2026_04")).isZero();

            // …with the tamper-evidence chain_hash copied verbatim (nothing rewritten).
            try (ResultSet rs = st.executeQuery("SELECT chain_hash FROM audit_entry_2026_03 WHERE actor = 'alice'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("hash-march");
            }
            // No rows were lost or duplicated across the whole parent.
            assertThat(count(st, "audit_entry")).isEqualTo(3);
        }
    }

    @Test
    void currentAndNextMonthAreCreatedAhead() throws Exception {
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            String thisMonth;
            String nextMonth;
            try (ResultSet rs =
                    st.executeQuery("SELECT to_char(now(),'YYYY_MM'), to_char(now() + interval '1 month','YYYY_MM')")) {
                rs.next();
                thisMonth = rs.getString(1);
                nextMonth = rs.getString(2);
            }
            assertThat(exists(st, "audit_entry_" + thisMonth)).isTrue();
            assertThat(exists(st, "audit_entry_" + nextMonth)).isTrue();
        }
    }

    @Test
    void appendOnlyGuardTriggerSurvivesTheCarve() throws Exception {
        // The carve must not have dropped/disabled the guard — a DELETE still raises.
        try (Connection c = owner();
                Statement st = c.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.executeUpdate("DELETE FROM audit_entry"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
        }
    }

    private static long count(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean exists(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT to_regclass('" + table + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}

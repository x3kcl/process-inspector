package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * S0 gate for M4-CLOSEOUT.md §5a / §A2 — proves {@code deploy/sql/audit-roles.sql} makes the
 * audit golden master genuinely append-only <b>at the grant layer</b>, not merely via the V1
 * guard trigger. Without a test the two-tier reality (dev = trigger-only, prod = trigger+grants)
 * rots silently (ops M3), so this exercises the REAL artifact through REAL psql:
 *
 * <ol>
 *   <li>Flyway migrates as the owner (the container superuser stands in for {@code inspector}).
 *   <li>{@code audit-roles.sql} runs via psql exactly as a DBA would, creating {@code
 *       inspector_app} (non-owner) + {@code inspector_ops}.
 *   <li>Connected AS {@code inspector_app}, every assertion below is a grant-layer fact:
 *       insufficient-privilege (SQLState 42501) fires <i>before</i> the trigger's custom
 *       exception (P0001) — so a 42501 on DELETE proves the GRANT blocks, not the trigger.
 * </ol>
 *
 * Pure Postgres/JDBC — no Flowable engine, no Spring context. Runs standalone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditRoleGrantsIT {

    /** Postgres insufficient_privilege — the grant layer refusing, distinct from the trigger. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static final String APP_ROLE = "inspector_app";
    private static final String APP_PASSWORD = "app-secret";

    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    void provision() throws Exception {
        postgres.start();

        // 1. Owner (superuser 'test') runs the schema — mirrors Flyway-as-inspector in prod.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // 2. The DBA step: run the real artifact through real psql, owner == the container user.
        postgres.copyFileToContainer(
                MountableFile.forHostPath("../deploy/sql/audit-roles.sql"), "/tmp/audit-roles.sql");
        Container.ExecResult result = postgres.execInContainer(
                "sh",
                "-c",
                "PGPASSWORD='" + postgres.getPassword() + "' psql -h 127.0.0.1 -U " + postgres.getUsername()
                        + " -d " + postgres.getDatabaseName() + " -v ON_ERROR_STOP=1"
                        + " -v owner=" + postgres.getUsername()
                        + " -v db=" + postgres.getDatabaseName()
                        + " -v app_role=" + APP_ROLE
                        + " -v ops_role=inspector_ops"
                        + " -v app_password=" + APP_PASSWORD
                        + " -v ops_password=ops-secret"
                        + " -f /tmp/audit-roles.sql");
        assertThat(result.getExitCode())
                .as("audit-roles.sql must apply cleanly:\n%s\n%s", result.getStdout(), result.getStderr())
                .isZero();
    }

    private Connection asOwner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Connection asApp() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    private static final String INSERT_PENDING =
            "INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome)"
                    + " VALUES (gen_random_uuid(), 'c', 'system', now(), '_inspector', 'test', 'PENDING')";

    @Test
    void appCanInsertAndCloseAnAuditRowButCannotRewriteOrDeleteHistory() throws Exception {
        try (Connection app = asApp();
                Statement st = app.createStatement()) {

            // CAN INSERT (needs both INSERT on the table AND USAGE on audit_entry_seq — the
            // sharpest omission the panel flagged: without the sequence USAGE the app cannot
            // write a single audit row).
            st.executeUpdate(INSERT_PENDING);

            // CAN move the outcome forward on the mutable columns (grant + trigger agree).
            int updated = st.executeUpdate("UPDATE audit_entry SET outcome = 'ok', http_status = 200"
                    + " WHERE actor = 'system' AND outcome = 'PENDING'");
            assertThat(updated).isEqualTo(1);

            // CANNOT rewrite an insert-time column — the column-scoped UPDATE grant refuses at
            // the grant layer (42501), before the trigger's immutability check even runs.
            assertThatThrownBy(() -> st.executeUpdate("UPDATE audit_entry SET actor = 'mallory'"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));

            // CANNOT DELETE — grant layer, not the append-only trigger (which would be P0001).
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM audit_entry"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));

            // CANNOT TRUNCATE — the footgun REVOKE DELETE alone does not close (security M8).
            assertThatThrownBy(() -> st.executeUpdate("TRUNCATE audit_entry"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        }
    }

    @Test
    void appManagesOperationalTablesButOnlyTheOwnerMayDropAuditPartitions() throws Exception {
        try (Connection app = asApp();
                Statement st = app.createStatement()) {
            // Operational tables carry no accountability guarantee — the app writes them freely,
            // incl. an identity-column table (proves no missing sequence grant).
            int inserted = st.executeUpdate("INSERT INTO protected_instance (engine_id, instance_id, reason,"
                    + " created_by, ts) VALUES ('engine-a', 'i-1', 'protected for a test', 'system', now())");
            assertThat(inserted).isEqualTo(1);
            assertThat(st.executeUpdate("INSERT INTO instance_note (engine_id, instance_id, author, ts, body)"
                            + " VALUES ('engine-a', 'i-1', 'system', now(), 'a note')"))
                    .isEqualTo(1);
            assertThat(st.executeQuery("SELECT count(*) FROM engine_registry").next())
                    .isTrue();
        }

        // Owner may create an audit partition; the app may NOT drop it (non-owner) NOR directly
        // rewrite an insert-time column on the CHILD — the ALTER DEFAULT PRIVILEGES grant must not
        // hand the app full-column UPDATE on owner-created partitions (Gemini S0).
        try (Connection owner = asOwner();
                Statement os = owner.createStatement()) {
            os.executeUpdate("CREATE TABLE audit_entry_2099_01 PARTITION OF audit_entry"
                    + " FOR VALUES FROM ('2099-01-01') TO ('2099-02-01')");
        }
        try (Connection app = asApp();
                Statement st = app.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("DROP TABLE audit_entry_2099_01"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            assertThatThrownBy(() -> st.executeUpdate("UPDATE audit_entry_2099_01 SET actor = 'mallory'"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        }
        try (Connection owner = asOwner();
                Statement os = owner.createStatement()) {
            os.executeUpdate("DROP TABLE audit_entry_2099_01"); // owner/ops path can reclaim
        }
    }
}

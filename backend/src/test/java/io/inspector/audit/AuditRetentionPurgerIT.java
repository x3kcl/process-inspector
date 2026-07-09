package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 for the retention purge: the whole slice end-to-end against a REAL Postgres 16 — the
 * {@link AuditRetentionPurger} orchestrator drives the REAL {@link AuditService} (checkpoint config
 * events into the tamper-evidence chain) and the REAL {@code purge_audit()} SECURITY DEFINER
 * function (the DROP), and a REAL {@link LegalHoldService} hold blocks it. No engines needed (purge
 * is pure DB), but boots the full context on the {@code it-actions} DB profile. LOCAL-ONLY (not in
 * ci.yml's itClass), like {@link AuditRoleGrantsIT} / {@link SnapshotSamplerIT}.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d  (context boot only).
 */
@SpringBootTest(properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditRetentionPurgerIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AuditRetentionPurger purger;

    @Autowired
    LegalHoldService legalHolds;

    @Autowired
    AuditEntryRepository auditRepo;

    @Autowired
    JdbcTemplate jdbc;

    private void seedAgedPartition(String name, String from, String to, String ts) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF audit_entry FOR VALUES FROM ('" + from
                + "') TO ('" + to + "')");
        jdbc.update("INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome, chain_hash)"
                + " VALUES (gen_random_uuid(), 'c', 'system', '" + ts + "', 'engine-a', 'retry-job', 'ok', 'seed')");
    }

    private boolean partitionExists(String name) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT to_regclass('" + name + "') IS NOT NULL", Boolean.class));
    }

    private List<AuditEntry> purgeEvents() {
        return auditRepo.findLog(null, "audit-retention-purge", null, null, Instant.EPOCH, PageRequest.of(0, 100));
    }

    @Test
    void dropsAnAgedPartitionAndAuditsTheChainCheckpointBeforeTheDrop() {
        seedAgedPartition("audit_entry_2019_06", "2019-06-01", "2019-07-01", "2019-06-15 09:00:00+00");
        assertThat(partitionExists("audit_entry_2019_06")).isTrue();

        purger.purge();

        assertThat(partitionExists("audit_entry_2019_06")).isFalse();
        // The checkpoint event for THIS partition names it and carries the chain boundary; a terminal
        // event records the count — all in the append-only ledger.
        // (jsonb re-serializes with a space after the colon, so match on tokens, not exact JSON.)
        assertThat(purgeEvents())
                .anySatisfy(e -> assertThat(e.getPayload())
                        .contains("checkpoint")
                        .contains("audit_entry_2019_06")
                        .contains("lastDroppedChainHash")
                        .contains("firstSurvivingChainHash"));
        assertThat(purgeEvents())
                .anySatisfy(e -> assertThat(e.getPayload()).contains("complete").contains("partitionsDropped"));
    }

    @Test
    void anActiveLegalHoldBlocksTheDropUntilReleased() {
        seedAgedPartition("audit_entry_2019_09", "2019-09-01", "2019-10-01", "2019-09-15 09:00:00+00");

        UUID hold = legalHolds.set(
                null,
                null,
                Instant.parse("2019-09-10T00:00:00Z"),
                Instant.parse("2019-09-20T00:00:00Z"),
                "case 4711 preservation",
                "alice");

        purger.purge();
        assertThat(partitionExists("audit_entry_2019_09"))
                .as("held partition must survive the purge")
                .isTrue();

        legalHolds.release(hold, "alice");
        purger.purge();
        assertThat(partitionExists("audit_entry_2019_09"))
                .as("released partition is now purgeable")
                .isFalse();
    }
}

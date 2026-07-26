package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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
 * Rung 4 for the R-SAFE-17 scoped audit-log query (issue #296): {@link
 * AuditEntryRepository#findLogScoped} is hand-authored JPQL with a correlated {@code engineId IN
 * (...)} predicate — proving it actually filters (as opposed to merely proving the controller
 * calls it with the right arguments, which the mocked-repository {@code AuditScopeApiSpringTest}
 * covers) needs a REAL Postgres. LOCAL-ONLY (not in ci.yml's itClass), like {@link
 * AuditRetentionPurgerIT} / {@link AuditRoleGrantsIT}.
 *
 * <p>Requires: docker compose -f docker/docker-compose.dev.yml up -d (context boot only — no
 * engine call is made).
 */
@SpringBootTest(properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditEntryRepositoryScopeIT {

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
    AuditEntryRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // TRUNCATE (not DELETE) — the V1 append-only guard is a row-level BEFORE DELETE trigger
        // and does not fire on TRUNCATE, exactly the same distinction V10's carve migration
        // itself relies on to empty audit_entry_default.
        jdbc.execute("TRUNCATE TABLE audit_entry");
    }

    private void seed(String engineId, String actor) {
        jdbc.update(
                "INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome,"
                        + " chain_hash) VALUES (gen_random_uuid(), gen_random_uuid()::text, ?, now(), ?, 'retry-job', 'ok',"
                        + " 'seed')",
                actor,
                engineId);
    }

    @Test
    void scopedQueryOnlyReturnsReadableEngines() {
        seed("engine-a", "alice");
        seed("engine-b", "bob");

        List<AuditEntry> onlyA = repository.findLogScoped(
                null, null, null, null, Instant.EPOCH, Set.of("engine-a"), false, PageRequest.of(0, 100));

        assertThat(onlyA).extracting(AuditEntry::getEngineId).containsExactly("engine-a");
    }

    @Test
    void configEventRowVisibleOnlyToFleetAdmin() {
        jdbc.update(
                "INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome,"
                        + " chain_hash) VALUES (gen_random_uuid(), gen_random_uuid()::text, 'system', now(),"
                        + " ?, 'config-scope-mapping-reload', 'ok', 'seed')",
                AuditService.CONFIG_ENGINE_ID);
        seed("engine-a", "alice");

        List<AuditEntry> nonAdmin = repository.findLogScoped(
                null, null, null, null, Instant.EPOCH, Set.of("engine-a"), false, PageRequest.of(0, 100));
        assertThat(nonAdmin).extracting(AuditEntry::getEngineId).containsExactly("engine-a");

        List<AuditEntry> admin = repository.findLogScoped(
                null, null, null, null, Instant.EPOCH, Set.of("engine-a"), true, PageRequest.of(0, 100));
        assertThat(admin)
                .extracting(AuditEntry::getEngineId)
                .containsExactlyInAnyOrder("engine-a", AuditService.CONFIG_ENGINE_ID);
    }

    @Test
    void nonEngineFiltersStillApplyUnderScope() {
        seed("engine-a", "alice");
        seed("engine-a", "bob");

        List<AuditEntry> aliceOnly = repository.findLogScoped(
                "alice", null, null, null, Instant.EPOCH, Set.of("engine-a"), false, PageRequest.of(0, 100));

        assertThat(aliceOnly).extracting(AuditEntry::getActor).containsExactly("alice");
    }

    @Test
    void anEmptyReadableSetSentinelExcludesEverything() {
        seed("engine-a", "alice");

        // Mirrors AuditController's NO_READABLE_ENGINES substitution: a caller with zero
        // readable engines must see nothing — not the config sentinel, not any real engine.
        List<AuditEntry> nothing = repository.findLogScoped(
                null, null, null, null, Instant.EPOCH, Set.of(" -no-readable-engines- "), true, PageRequest.of(0, 100));

        assertThat(nothing).isEmpty();
    }
}

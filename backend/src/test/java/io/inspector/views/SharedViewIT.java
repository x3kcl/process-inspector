package io.inspector.views;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the {@code shared_view} store (V12) against a REAL Postgres 16
 * (Testcontainers) — Flyway applied, Hibernate validating the {@link SharedView} entity, the actual
 * JDBC path. Proves what a mocked rung-3 test cannot: the {@code UNIQUE(name, scope_engine_id,
 * scope_tenant_id)} canon identity, the {@code NOT NULL DEFAULT '*'} scope columns (a NULL would fall
 * out of the unique index and let duplicate canon in — SHARED-VIEWS.md §4.1), and that a shared view
 * and a per-user {@code saved_view} may carry the SAME name (distinct namespaces — the picker badges
 * "Team").
 *
 * <p>DB-only (no engine call) — LOCAL-ONLY like the other DB ITs (not in ci.yml itClass); CI covers
 * the logic via the rung-1/3 suite.
 */
@SpringBootTest
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedViewIT {

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
    SharedViewRepository shared;

    @Autowired
    SavedViewRepository saved;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // PER_CLASS shares one DB with no rollback — isolate each test (some assert over findAll()).
        shared.deleteAll();
        saved.deleteAll();
    }

    private SharedView view(String author, String name, String search, String engine, String tenant) {
        return new SharedView(author, name, search, engine, tenant, null, null, Instant.now());
    }

    @Test
    void canonIsUniquePerNameAndScope() {
        shared.saveAndFlush(view("alice", "Stuck payments", "status=FAILED", "orders-prod", "*"));

        // Same (name, scope) → the create-only identity is violated (an overwrite must be a
        // moderation act, S3 — never a second row).
        assertThatThrownBy(
                        () -> shared.saveAndFlush(view("bob", "Stuck payments", "status=RETRYING", "orders-prod", "*")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Same name, DIFFERENT scope → a distinct canon, allowed.
        shared.saveAndFlush(view("carol", "Stuck payments", "status=FAILED", "billing-prod", "*"));
        assertThat(shared.findAllByOrderByCreatedAtDesc())
                .extracting(SharedView::getScopeEngineId)
                .containsExactlyInAnyOrder("orders-prod", "billing-prod");
    }

    @Test
    void scopeColumnsDefaultToTheGlobalWildcardNotNull() {
        // Insert bypassing the entity's constructor defaults — a bare row must land on '*','*',
        // never NULL (a NULL scope would escape the unique index — the uniqueness hole guard).
        jdbc.update(
                "INSERT INTO shared_view (author, name, search, created_at, updated_at) "
                        + "VALUES (?, ?, ?, now(), now())",
                "dave",
                "Failed last hour",
                "status=FAILED");

        SharedView row = shared.findAllByOrderByCreatedAtDesc().stream()
                .filter(v -> v.getName().equals("Failed last hour"))
                .findFirst()
                .orElseThrow();
        assertThat(row.getScopeEngineId()).isEqualTo("*");
        assertThat(row.getScopeTenantId()).isEqualTo("*");
    }

    @Test
    void sharedAndPrivateViewsShareANameInDistinctNamespaces() {
        saved.saveAndFlush(new SavedView("erin", "Stuck payments", "status=SUSPENDED", Instant.now()));
        // Same name as erin's PRIVATE view — the shared namespace is independent, no collision.
        shared.saveAndFlush(view("erin", "Stuck payments", "status=FAILED", "*", "*"));

        assertThat(saved.findByOwnerAndName("erin", "Stuck payments")).isPresent();
        assertThat(shared.findByNameAndScopeEngineIdAndScopeTenantId("Stuck payments", "*", "*"))
                .isPresent();
    }
}

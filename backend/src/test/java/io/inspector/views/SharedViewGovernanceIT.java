package io.inspector.views;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the shared-view GOVERNANCE lifecycle (S3, R-SAFE-16) against a REAL
 * Postgres 16 with the REAL {@code AuditService} — proves each transition writes exactly one
 * config-event row, that the payload carries a search HASH (never the raw query text, §4.4 A5), and
 * the load-bearing NEGATIVE: a PRIVATE-view write emits NO audit row (the preference→governance line
 * is the table boundary, §4.4 / A3). DB-only, LOCAL-ONLY (not in ci.yml itClass); CI covers the gate
 * logic via the rung-1 suite.
 */
@SpringBootTest
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedViewGovernanceIT {

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
    SharedViewService service;

    @Autowired
    SharedViewRepository repository;

    @Autowired
    ViewStoreService privateViews;

    @Autowired
    AuditEntryRepository audit;

    @BeforeEach
    void clean() {
        repository.deleteAll(); // audit_entry is append-only — never cleaned; assert on deltas/rows.
    }

    private static Authentication admin(String user) {
        return new UsernamePasswordAuthenticationToken(user, "x", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private long viewAudits(String action, String nameFragment) {
        return audit.findAll().stream()
                .filter(e -> action.equals(e.getAction()))
                .filter(e -> e.getPayload() != null && e.getPayload().contains(nameFragment))
                .count();
    }

    @Test
    void publishWritesAViewPublishRowWithHashedSearchNotRawText() {
        SharedView v = service.publish(
                admin("alice"),
                "Stuck payments",
                "engines=orders-prod&businessKey=SECRET-ORDER-42",
                "the payments runbook",
                "https://runbook.example/payments",
                null,
                null);

        assertThat(repository.findById(v.getId())).isPresent();
        AuditEntry row = audit.findAll().stream()
                .filter(e -> "view-publish".equals(e.getAction()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(row.getActor()).isEqualTo("alice");
        assertThat(row.getPayload()).contains("searchSha256").contains("\"visibilityAfter\": \"SHARED\"");
        // The raw search embeds a business key — it must NEVER appear in the audit payload.
        assertThat(row.getPayload()).doesNotContain("SECRET-ORDER-42");
    }

    @Test
    void editWritesViewUpdateAndUnpublishRemovesRowAndWritesViewUnpublish() {
        SharedView v = service.publish(
                admin("alice"), "Failing onboarding", "engines=hr-prod&status=FAILED", null, null, null, null);

        service.edit(admin("alice"), v.getId(), "engines=hr-prod&status=RETRYING", "now retrying", null, null);
        assertThat(repository.findById(v.getId()).orElseThrow().getSearch())
                .isEqualTo("engines=hr-prod&status=RETRYING");
        assertThat(viewAudits("view-update", "Failing onboarding")).isEqualTo(1);

        // W2 #3 (R-SAFE-16): unpublish is a moderation verb — reason ≥10 required for EVERY
        // caller (author included), and it lands in the row's reason COLUMN, not only the payload.
        assertThatThrownBy(() -> service.unpublish(admin("alice"), v.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThat(repository.findById(v.getId())).isPresent(); // refused → nothing changed

        service.unpublish(admin("alice"), v.getId(), "superseded by the new onboarding canon");
        assertThat(repository.findById(v.getId())).isEmpty();
        assertThat(viewAudits("view-unpublish", "Failing onboarding")).isEqualTo(1);
        AuditEntry unpublishRow = audit.findAll().stream()
                .filter(e -> "view-unpublish".equals(e.getAction()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(unpublishRow.getReason()).isEqualTo("superseded by the new onboarding canon");
    }

    @Test
    void publishIsCreateOnlyAndConflictsWith409() {
        service.publish(admin("alice"), "Peak load", "engines=orders-prod&status=FAILED", null, null, null, null);
        assertThatThrownBy(() -> service.publish(
                        admin("bob"), "Peak load", "engines=orders-prod&status=SUSPENDED", null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void publishRefusesAScopeThatWouldReachOutsideItsLabel() {
        // Declared scope engine-A, but the search ALSO targets engine-B → content leaks the label.
        assertThatThrownBy(() -> service.publish(
                        admin("alice"),
                        "Cross-engine",
                        "engines=orders-prod,billing-prod&status=FAILED",
                        null,
                        null,
                        "orders-prod",
                        "*"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void privateViewWritesNoAuditRow_theGovernanceBoundary() {
        long before = audit.count();
        privateViews.saveView("alice", "My private bookmark", "status=FAILED");
        List<SavedView> mine = privateViews.listViews("alice");
        privateViews.deleteView("alice", mine.get(0).getId());
        // Not one audit row for the whole private CRUD cycle — the preference→governance line holds.
        assertThat(audit.count()).isEqualTo(before);
    }
}

package io.inspector.views;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the Saved Views + Recents store against a REAL Postgres 16
 * (Testcontainers) — Flyway V6 applied, Hibernate validating, the actual JDBC path. Proves what
 * the mocked rung-3 test cannot: the {@code UNIQUE(owner,name)} upsert-by-name, ownership-scoped
 * delete + list ISOLATION between two users, and the recents dedupe + cap against real SQL.
 *
 * <p>DB-only (no engine call) — LOCAL-ONLY like the other DB+engine ITs (not in ci.yml itClass);
 * CI covers the logic via the rung-1/3 suite.
 */
@SpringBootTest
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ViewStoreIT {

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
    ViewStoreService store;

    @Test
    void savedViewsAreUpsertedByNameAndIsolatedPerOwner() {
        store.saveView("alice", "Payments", "status=FAILED");
        store.saveView("alice", "Payments", "status=RETRYING"); // same name → replace, not duplicate
        store.saveView("bob", "Payments", "status=ACTIVE"); // bob's own namespace

        List<SavedView> alice = store.listViews("alice");
        assertThat(alice)
                .singleElement()
                .satisfies(v -> assertThat(v.getSearch()).isEqualTo("status=RETRYING"));
        assertThat(store.listViews("bob"))
                .singleElement()
                .satisfies(v -> assertThat(v.getSearch()).isEqualTo("status=ACTIVE"));

        // bob cannot delete alice's view; alice can.
        Long aliceId = alice.get(0).getId();
        assertThat(store.deleteView("bob", aliceId)).isFalse();
        assertThat(store.listViews("alice")).hasSize(1);
        assertThat(store.deleteView("alice", aliceId)).isTrue();
        assertThat(store.listViews("alice")).isEmpty();
    }

    @Test
    void recentsDedupeAndCapAtTenNewestFirst() {
        // 12 distinct searches for carol, oldest→newest so the last recorded is newest.
        IntStream.range(0, 12).forEach(i -> store.recordRecent("carol", "s" + i, "label " + i));
        // Re-record an existing one — must bump, not duplicate.
        List<RecentSearch> after = store.recordRecent("carol", "s11", "label 11 again");

        assertThat(after).hasSize(10);
        assertThat(after.get(0).getSearch()).isEqualTo("s11"); // most recent
        assertThat(after.get(0).getLabel()).isEqualTo("label 11 again"); // touched
        assertThat(after).extracting(RecentSearch::getSearch).doesNotHaveDuplicates();
        assertThat(store.listRecents("carol")).hasSize(10);
    }
}

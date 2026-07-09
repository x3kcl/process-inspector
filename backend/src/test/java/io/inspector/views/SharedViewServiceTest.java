package io.inspector.views;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: {@link SharedViewService#filterVisible} — the read-visibility filter over crafted
 * {@link ScopeGrant} sets (SHARED-VIEWS.md §4.3). These are the scoped cases the dev basic-auth
 * ladder cannot express (it only mints global grants), so they live here, not in a rung-3 door test.
 */
class SharedViewServiceTest {

    private static SharedView view(String name, String engine, String tenant) {
        return new SharedView("author", name, "engines=" + engine, engine, tenant, null, null, Instant.now());
    }

    private final SharedView ordersProd = view("Stuck payments", "orders-prod", "tenant-a");
    private final SharedView hrProd = view("Stuck onboarding", "hr-prod", "tenant-b");
    private final SharedView globalCanon =
            new SharedView("author", "Failed last hour", "status=FAILED", "*", "*", null, null, Instant.now());
    private final List<SharedView> all = List.of(ordersProd, hrProd, globalCanon);

    @Test
    void perEngineViewerSeesOwnEngineAndGlobalButNotOtherTenant() {
        Set<ScopeGrant> grants = Set.of(new ScopeGrant(Role.VIEWER, "orders-prod", "tenant-a"));
        assertThat(SharedViewService.filterVisible(grants, all)).containsExactly(ordersProd, globalCanon);
    }

    @Test
    void tenantViewerSeesItsTenantAndGlobal() {
        Set<ScopeGrant> grants = Set.of(new ScopeGrant(Role.VIEWER, "*", "tenant-b"));
        assertThat(SharedViewService.filterVisible(grants, all)).containsExactly(hrProd, globalCanon);
    }

    @Test
    void globalOperatorSeesEverything() {
        Set<ScopeGrant> grants = Set.of(ScopeGrant.global(Role.OPERATOR));
        assertThat(SharedViewService.filterVisible(grants, all)).containsExactlyElementsOf(all);
    }

    @Test
    void noGrantsSeesNothing() {
        assertThat(SharedViewService.filterVisible(Set.of(), all)).isEmpty();
    }

    @Test
    void multipleGrantsUnionTheirVisibility() {
        Set<ScopeGrant> grants = Set.of(
                new ScopeGrant(Role.VIEWER, "orders-prod", "tenant-a"),
                new ScopeGrant(Role.VIEWER, "hr-prod", "tenant-b"));
        assertThat(SharedViewService.filterVisible(grants, all)).containsExactly(ordersProd, hrProd, globalCanon);
    }

    /* ---------------- canPublish gate (S3, §4.3) ---------------- */

    @Test
    void scopedOperatorPublishesInScopeButNotGlobal() {
        Set<ScopeGrant> grants = Set.of(new ScopeGrant(Role.OPERATOR, "orders-prod", "tenant-a"));
        // Concrete in-scope publish: allowed at the OPERATOR floor.
        assertThat(SharedViewService.canPublish(grants, "orders-prod", "tenant-a"))
                .isTrue();
        // Global scope is wildcard → floor escalates to ADMIN → a scoped OPERATOR is refused.
        assertThat(SharedViewService.canPublish(grants, "*", "*")).isFalse();
        // Another engine: not covered.
        assertThat(SharedViewService.canPublish(grants, "billing-prod", "tenant-a"))
                .isFalse();
    }

    @Test
    void wildcardScopePublishNeedsAdminNotMerelyGlobalOperator() {
        // A GLOBAL operator can publish concrete scopes but NOT a wildcard scope (R-SAFE-14 breadth).
        Set<ScopeGrant> globalOperator = Set.of(ScopeGrant.global(Role.OPERATOR));
        assertThat(SharedViewService.canPublish(globalOperator, "orders-prod", "tenant-a"))
                .isTrue();
        assertThat(SharedViewService.canPublish(globalOperator, "*", "*")).isFalse();
        assertThat(SharedViewService.canPublish(globalOperator, "*", "tenant-a"))
                .isFalse();

        Set<ScopeGrant> globalAdmin = Set.of(ScopeGrant.global(Role.ADMIN));
        assertThat(SharedViewService.canPublish(globalAdmin, "*", "*")).isTrue();
        assertThat(SharedViewService.canPublish(globalAdmin, "*", "tenant-a")).isTrue();
    }

    /* ---------------- canModerate authority (S3, §4.4) ---------------- */

    @Test
    void authorAlwaysModeratesOwnCanonEvenWithNoGrant() {
        assertThat(SharedViewService.canModerate(Set.of(), "alice", "alice", "orders-prod", "tenant-a"))
                .isTrue();
    }

    @Test
    void nonAuthorNeedsAdminOnTheCanonScope() {
        String author = "alice";
        String caller = "bob";
        // A mere OPERATOR (even in-scope) cannot moderate another's canon.
        assertThat(SharedViewService.canModerate(
                        Set.of(new ScopeGrant(Role.OPERATOR, "orders-prod", "tenant-a")),
                        author,
                        caller,
                        "orders-prod",
                        "tenant-a"))
                .isFalse();
        // ADMIN on the scope may moderate.
        assertThat(SharedViewService.canModerate(
                        Set.of(new ScopeGrant(Role.ADMIN, "orders-prod", "tenant-a")),
                        author,
                        caller,
                        "orders-prod",
                        "tenant-a"))
                .isTrue();
        // ADMIN on a DIFFERENT engine may not.
        assertThat(SharedViewService.canModerate(
                        Set.of(new ScopeGrant(Role.ADMIN, "billing-prod", "tenant-a")),
                        author,
                        caller,
                        "orders-prod",
                        "tenant-a"))
                .isFalse();
    }
}

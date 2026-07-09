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
}

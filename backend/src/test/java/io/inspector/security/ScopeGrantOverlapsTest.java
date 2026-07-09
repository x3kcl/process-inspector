package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Rung 1: {@link ScopeGrant#overlaps} — the read-visibility INTERSECTION predicate for shared views
 * (SHARED-VIEWS.md §4.3, R-SEM-24). The load-bearing distinction from {@link ScopeGrant#covers}: a
 * concrete grant OVERLAPS a global scope (so global canon is visible to per-engine viewers) but does
 * NOT COVER it (so publishing global canon still needs a global grant, S3). These crafted-grant cases
 * are the scoped RBAC matrix the dev basic-auth ladder cannot express (it only mints global grants).
 */
class ScopeGrantOverlapsTest {

    private static ScopeGrant grant(Role role, String engine, String tenant) {
        return new ScopeGrant(role, engine, tenant);
    }

    @Test
    void globalGrantSeesEveryScope() {
        ScopeGrant global = ScopeGrant.global(Role.VIEWER);
        assertThat(global.overlaps(Role.VIEWER, "orders-prod", "tenant-a")).isTrue();
        assertThat(global.overlaps(Role.VIEWER, "*", "*")).isTrue();
        assertThat(global.overlaps(Role.VIEWER, "*", "tenant-b")).isTrue();
    }

    @Test
    void perEngineGrantSeesGlobalCanon() {
        // The inverse of covers(): a concrete grant OVERLAPS the '*'/'*' scope → global canon is
        // visible to a per-engine viewer (covers() would hide it — the panel's W2 finding).
        ScopeGrant perEngine = grant(Role.VIEWER, "orders-prod", "tenant-a");
        assertThat(perEngine.overlaps(Role.VIEWER, "*", "*")).isTrue();
        assertThat(perEngine.covers(Role.VIEWER, "*", "*")).isFalse();
    }

    @Test
    void perEngineGrantSeesItsOwnEngineButNotAnother() {
        ScopeGrant ordersProd = grant(Role.VIEWER, "orders-prod", "*");
        assertThat(ordersProd.overlaps(Role.VIEWER, "orders-prod", "tenant-a")).isTrue();
        assertThat(ordersProd.overlaps(Role.VIEWER, "billing-prod", "tenant-a")).isFalse();
    }

    @Test
    void tenantScopedGrantSeesItsTenantAndGlobalButNotAnotherTenant() {
        ScopeGrant tenantA = grant(Role.VIEWER, "*", "tenant-a");
        assertThat(tenantA.overlaps(Role.VIEWER, "*", "tenant-a")).isTrue();
        assertThat(tenantA.overlaps(Role.VIEWER, "orders-prod", "tenant-a")).isTrue();
        assertThat(tenantA.overlaps(Role.VIEWER, "*", "*")).isTrue(); // global canon visible
        assertThat(tenantA.overlaps(Role.VIEWER, "*", "tenant-b")).isFalse();
    }

    @Test
    void roleFloorIsHonored() {
        // overlaps is used at the VIEWER floor for read-visibility; a below-floor check still fails.
        ScopeGrant viewer = grant(Role.VIEWER, "*", "*");
        assertThat(viewer.overlaps(Role.OPERATOR, "orders-prod", "tenant-a")).isFalse();
        assertThat(grant(Role.ADMIN, "orders-prod", "tenant-a").overlaps(Role.VIEWER, "orders-prod", "tenant-a"))
                .isTrue();
    }
}

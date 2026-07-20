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

    /* ---------------- publishRefusal detail (#247) ---------------- */

    @Test
    void concreteScopeRefusalNamesOperatorOnlyAndTheActualScopeAndStanding() {
        // A VIEWER on the scope: the refusal names OPERATOR (the one tier this shape needs),
        // the real scope literal, and the caller's own standing — never the wildcard/ADMIN branch.
        Set<ScopeGrant> grants = Set.of(new ScopeGrant(Role.VIEWER, "orders-prod", "tenant-a"));
        String detail = SharedViewService.publishRefusal(grants, "orders-prod", "tenant-a");
        assertThat(detail)
                .isEqualTo("publishing scope orders-prod/tenant-a needs an OPERATOR grant covering it"
                        + " — your best grant covering it is VIEWER");
        assertThat(detail).doesNotContain("ADMIN").doesNotContain("wildcard");
    }

    @Test
    void concreteScopeRefusalWithNoCoveringGrantSaysSo() {
        Set<ScopeGrant> grants = Set.of(new ScopeGrant(Role.OPERATOR, "orders-prod", "tenant-a"));
        assertThat(SharedViewService.publishRefusal(grants, "billing-prod", "tenant-a"))
                .isEqualTo("publishing scope billing-prod/tenant-a needs an OPERATOR grant covering it"
                        + " — you have no grant covering it");
    }

    @Test
    void wildcardScopeRefusalNamesAdminOnlyAndTheCallersActualStanding() {
        // A GLOBAL operator attempting the wildcard scope: ADMIN is the requirement named — no
        // "(or ...)" alternative — and the caller's own OPERATOR standing appears only as standing.
        Set<ScopeGrant> globalOperator = Set.of(ScopeGrant.global(Role.OPERATOR));
        String detail = SharedViewService.publishRefusal(globalOperator, "*", "*");
        assertThat(detail)
                .isEqualTo("publishing the wildcard scope */* needs an ADMIN grant covering it"
                        + " — your best grant covering it is OPERATOR");

        // A scoped operator holds nothing that contains a wildcard scope at all.
        Set<ScopeGrant> scoped = Set.of(new ScopeGrant(Role.OPERATOR, "orders-prod", "tenant-a"));
        assertThat(SharedViewService.publishRefusal(scoped, "*", "tenant-a"))
                .isEqualTo("publishing the wildcard scope */tenant-a needs an ADMIN grant covering it"
                        + " — you have no grant covering it");
    }

    @Test
    void wildcardRefusalOnAnUntenantedEngineNamesTheDeadEndExplicitly() {
        // #276: the engine is already narrowed to ONE concrete value (not "*") but the scope is
        // still wildcard because the tenant derived to "*" — this engine carries no tenant pin.
        // That's structurally different from a missing-grant refusal: no further narrowing is
        // possible, and the message must say so instead of reading as an ordinary grant gap.
        Set<ScopeGrant> globalOperator = Set.of(ScopeGrant.global(Role.OPERATOR));
        String detail = SharedViewService.publishRefusal(globalOperator, "orders-prod", "*");
        assertThat(detail)
                .isEqualTo("publishing the wildcard scope orders-prod/* needs an ADMIN grant covering it"
                        + " — your best grant covering it is OPERATOR"
                        + " — this engine has no tenant pin, so its scope is always wildcard-breadth;"
                        + " narrowing to this engine alone cannot lower the floor below ADMIN (add a tenant pin"
                        + " on the engine's registry entry to unlock a scoped OPERATOR publish)");

        // Engine wildcard (fleet-wide, intentional) does NOT get the untenanted-derivation clause —
        // narrowing the engine there genuinely would change the outcome, so it stays silent.
        assertThat(SharedViewService.publishRefusal(globalOperator, "*", "*")).doesNotContain("no tenant pin");
        assertThat(SharedViewService.publishRefusal(globalOperator, "*", "tenant-a"))
                .doesNotContain("no tenant pin");
    }

    @Test
    void publishFloorEscalatesToAdminForAnyWildcardShapeOnly() {
        assertThat(SharedViewService.publishFloor("orders-prod", "tenant-a")).isEqualTo(Role.OPERATOR);
        assertThat(SharedViewService.publishFloor("*", "*")).isEqualTo(Role.ADMIN);
        assertThat(SharedViewService.publishFloor("*", "tenant-a")).isEqualTo(Role.ADMIN);
        assertThat(SharedViewService.publishFloor("orders-prod", "*")).isEqualTo(Role.ADMIN);
    }

    /* ---------------- dangling-canon detection (S4, §4.5) ---------------- */

    @Test
    void concreteScopeEngineNotLiveIsDangling() {
        assertThat(SharedViewService.danglingReason("orders-prod", java.util.Set.of("orders-prod", "billing-prod")))
                .isNull();
        assertThat(SharedViewService.danglingReason("orders-prod", java.util.Set.of("billing-prod")))
                .contains("not currently available");
        assertThat(SharedViewService.danglingReason("orders-prod", java.util.Set.of()))
                .contains("orders-prod");
    }

    @Test
    void wildcardScopeNeverDanglesOnAnEngine() {
        assertThat(SharedViewService.danglingReason("*", java.util.Set.of())).isNull();
        assertThat(SharedViewService.danglingReason("*", java.util.Set.of("orders-prod")))
                .isNull();
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

    /* ---------- unpublish reason floor (usability W2 #3, R-SAFE-16: a moderation verb) ---------- */

    @Test
    void unpublishReasonIsRequiredForEveryCallerIncludingTheAuthor() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SharedViewService.requireUnpublishReason(null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("reason");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SharedViewService.requireUnpublishReason("   "))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SharedViewService.requireUnpublishReason("too short"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void unpublishReasonAtTheFloorIsAcceptedAndTrimmed() {
        assertThat(SharedViewService.requireUnpublishReason("  superseded by the new canon  "))
                .isEqualTo("superseded by the new canon");
    }
}

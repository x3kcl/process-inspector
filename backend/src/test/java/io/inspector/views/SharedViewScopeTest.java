package io.inspector.views;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.views.SharedViewScope.Scope;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: {@link SharedViewScope} — the scope-of-content ↔ scope-of-governance binding
 * (SHARED-VIEWS.md §4.2, W1). Proves the derived scope is the tightest tuple covering the search's
 * engines, and that {@link SharedViewScope#contains} refuses a declared scope that would leak an
 * engine outside its label (the fatal flaw the panel + Gemini surfaced).
 */
class SharedViewScopeTest {

    private static final Function<String, String> TENANTS =
            id -> Map.of("orders-prod", "tenant-a", "billing-prod", "tenant-a", "hr-prod", "tenant-b", "engine-a", "")
                    .get(id);

    @Test
    void referencedEnginesParsesTheCanonicalEnginesKey() {
        assertThat(SharedViewScope.referencedEngines("engines=orders-prod,billing-prod&status=FAILED"))
                .containsExactly("orders-prod", "billing-prod");
        assertThat(SharedViewScope.referencedEngines("status=FAILED")).isEmpty();
        assertThat(SharedViewScope.referencedEngines("")).isEmpty();
        // URL-encoded key/value survive decoding; blanks in the list are dropped.
        assertThat(SharedViewScope.referencedEngines("engines=orders-prod%2C,%20hr-prod"))
                .containsExactly("orders-prod", "hr-prod");
    }

    @Test
    void deriveGlobalWhenNoEngine() {
        assertThat(SharedViewScope.derive(Set.of(), TENANTS)).isEqualTo(Scope.global());
    }

    @Test
    void deriveSingleEnginePinsItsTenant() {
        assertThat(SharedViewScope.derive(Set.of("orders-prod"), TENANTS))
                .isEqualTo(new Scope("orders-prod", "tenant-a"));
    }

    @Test
    void deriveSingleUntenantedEngineIsEngineWildcardTenant() {
        assertThat(SharedViewScope.derive(Set.of("engine-a"), TENANTS)).isEqualTo(new Scope("engine-a", "*"));
    }

    @Test
    void deriveManyEnginesOneTenantIsTenantWildcardEngine() {
        assertThat(SharedViewScope.derive(Set.of("orders-prod", "billing-prod"), TENANTS))
                .isEqualTo(new Scope("*", "tenant-a"));
    }

    @Test
    void deriveManyEnginesAcrossTenantsIsGlobal() {
        assertThat(SharedViewScope.derive(Set.of("orders-prod", "hr-prod"), TENANTS))
                .isEqualTo(Scope.global());
    }

    @Test
    void containsBindsContentToGovernance() {
        Set<String> content = Set.of("orders-prod");
        // A scope on the same engine contains it; a scope on a different engine does not.
        assertThat(SharedViewScope.contains("orders-prod", "tenant-a", content, TENANTS))
                .isTrue();
        assertThat(SharedViewScope.contains("billing-prod", "tenant-a", content, TENANTS))
                .isFalse();
        // A tenant-wildcard scope of the right tenant contains it; the wrong tenant does not.
        assertThat(SharedViewScope.contains("*", "tenant-a", content, TENANTS)).isTrue();
        assertThat(SharedViewScope.contains("*", "tenant-b", content, TENANTS)).isFalse();
        // Global contains anything.
        assertThat(SharedViewScope.contains("*", "*", content, TENANTS)).isTrue();
    }

    @Test
    void containsRefusesAScopeThatWouldLeakAProdEngine() {
        // "engine-A"-labelled canon whose search ALSO targets billing-prod — the leak W1 forbids.
        Set<String> content = Set.of("orders-prod", "billing-prod");
        assertThat(SharedViewScope.contains("orders-prod", "tenant-a", content, TENANTS))
                .isFalse();
        // The honest scope for that content is the shared tenant (or global).
        assertThat(SharedViewScope.contains("*", "tenant-a", content, TENANTS)).isTrue();
    }

    @Test
    void allEnginesSearchIsContainedOnlyByGlobal() {
        Set<String> allEngines = Set.of(); // empty engines key = all enabled engines
        assertThat(SharedViewScope.contains("*", "*", allEngines, TENANTS)).isTrue();
        assertThat(SharedViewScope.contains("orders-prod", "tenant-a", allEngines, TENANTS))
                .isFalse();
        assertThat(SharedViewScope.contains("*", "tenant-a", allEngines, TENANTS))
                .isFalse();
    }
}

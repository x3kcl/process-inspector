package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 (pure): the read-scope gate (S2, R-SAFE-17). Isolates the flag gate + the
 * {@link ScopeGrant#overlaps} intersection that decides which engines a caller may READ. The
 * grant resolution itself ({@link RbacAuthorizer#grantsFor}) is proven elsewhere, so it is
 * mocked here to a crafted grant set — the interesting logic under test is the narrowing.
 */
class ReadScopeGateTest {

    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final Authentication auth = mock(Authentication.class);

    private final EngineConfig engineA = TestEngines.engineInTenant("engine-a", "http://a", "tenant-a");
    private final EngineConfig engineB = TestEngines.engineInTenant("engine-b", "http://b", "tenant-b");
    private final EngineConfig canon = TestEngines.engineInTenant("shared", "http://s", "*");

    private ReadScopeGate gate(boolean enforced) {
        SecurityProperties props = new SecurityProperties(null, null, null, null, null, enforced);
        when(registry.all()).thenReturn(List.of(engineA, engineB, canon));
        return new ReadScopeGate(rbac, registry, props);
    }

    @Test
    void offReturnsNullMeaningUnrestricted() {
        // Enforcement off: the gate must return null (= "do not filter"), never an empty set — the
        // whole legacy fleet-wide read path depends on null being the unrestricted sentinel.
        ReadScopeGate gate = gate(false);
        assertThat(gate.enforced()).isFalse();
        assertThat(gate.readableEngineIds(auth)).isNull();
    }

    @Test
    void globalGrantSeesEveryEngine() {
        when(rbac.grantsFor(auth)).thenReturn(Set.of(ScopeGrant.global(Role.VIEWER)));
        // On, but a global ('*'/'*') grant overlaps every engine — narrowing is a no-op.
        assertThat(gate(true).readableEngineIds(auth)).containsExactlyInAnyOrder("engine-a", "engine-b", "shared");
    }

    @Test
    void perEngineViewerSeesOnlyThatEngine() {
        when(rbac.grantsFor(auth)).thenReturn(Set.of(new ScopeGrant(Role.VIEWER, "engine-a", "*")));
        // engine-b is another engine/tenant — the S2 exposure the gate closes.
        assertThat(gate(true).readableEngineIds(auth)).containsExactly("engine-a");
    }

    @Test
    void perTenantGrantAlsoOverlapsTheGlobalCanonEngine() {
        // A tenant-a grant overlaps engine-a (its tenant) AND the '*'-tenant shared canon engine, but
        // NOT tenant-b — the overlaps() read-visibility semantics, applied to real engine rows.
        when(rbac.grantsFor(auth)).thenReturn(Set.of(new ScopeGrant(Role.VIEWER, "*", "tenant-a")));
        assertThat(gate(true).readableEngineIds(auth)).containsExactlyInAnyOrder("engine-a", "shared");
    }

    @Test
    void subViewerRoleDoesNotClearTheViewerFloor() {
        // There is no role below VIEWER, but a grant must still meet the VIEWER floor to read — an
        // empty grant set (no authenticated scope) reads nothing.
        when(rbac.grantsFor(auth)).thenReturn(Set.of());
        assertThat(gate(true).readableEngineIds(auth)).isEmpty();
    }
}

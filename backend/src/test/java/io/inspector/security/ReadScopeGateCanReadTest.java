package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 (#740): {@link ReadScopeGate#canRead} — the per-engine twin of {@code readableEngineIds}
 * for a path-named read and the engine DISPLAY list, which also lists disabled engines.
 */
class ReadScopeGateCanReadTest {

    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final Authentication auth = mock(Authentication.class);

    private final EngineConfig engineA = TestEngines.engineInTenant("engine-a", "http://a", "tenant-a");
    private final EngineConfig engineB = TestEngines.engineInTenant("engine-b", "http://b", "tenant-b");
    private final EngineConfig disabledA = TestEngines.builder("engine-a2", "http://a2")
            .tenantId("tenant-a")
            .enabled(false)
            .build();

    private ReadScopeGate gate(boolean enforced) {
        EngineRegistry registry =
                new EngineRegistry(new InspectorProperties(4, 10, null, null, List.of(engineA, engineB, disabledA)));
        return new ReadScopeGate(
                rbac, registry, new SecurityProperties(null, null, null, null, null, enforced, null, null));
    }

    @Test
    void offReadsEverything() {
        when(rbac.grantsFor(auth)).thenReturn(Set.of());
        assertThat(gate(false).canRead(auth, "engine-b")).isTrue();
    }

    @Test
    void perEngineGrantReadsOnlyThatEngine() {
        when(rbac.grantsFor(auth)).thenReturn(Set.of(new ScopeGrant(Role.ADMIN, "engine-a", "*")));
        ReadScopeGate gate = gate(true);
        assertThat(gate.canRead(auth, "engine-a")).isTrue();
        assertThat(gate.canRead(auth, "engine-b")).isFalse();
    }

    @Test
    void tenantGrantReadsADisabledEngineOfItsTenant() {
        when(rbac.grantsFor(auth)).thenReturn(Set.of(new ScopeGrant(Role.VIEWER, "*", "tenant-a")));
        ReadScopeGate gate = gate(true);
        assertThat(gate.canRead(auth, "engine-a2")).isTrue();
        assertThat(gate.canRead(auth, "engine-b")).isFalse();
    }

    @Test
    void unknownEngineIsUnreadableForAScopedGrantButNotForAGlobalOne() {
        // Same as the @PreAuthorize atLeastOn reads: a scoped caller cannot tell unknown from out of scope.
        when(rbac.grantsFor(auth)).thenReturn(Set.of(new ScopeGrant(Role.VIEWER, "engine-a", "*")));
        assertThat(gate(true).canRead(auth, "no-such")).isFalse();
        when(rbac.grantsFor(auth)).thenReturn(Set.of(ScopeGrant.global(Role.VIEWER)));
        assertThat(gate(true).canRead(auth, "no-such")).isTrue();
    }

    @Test
    void noGrantsReadNothing() {
        when(rbac.grantsFor(auth)).thenReturn(Set.of());
        assertThat(gate(true).canRead(auth, "engine-a")).isFalse();
    }
}

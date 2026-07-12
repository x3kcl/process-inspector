package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.dto.LeakViewsResponse;
import io.inspector.dto.LeakViewsResponse.LeakDefinitionCount;
import io.inspector.dto.LeakViewsResponse.LeakDefinitionCount.EngineLeakCount;
import io.inspector.dto.LeakViewsResponse.LeakWindows;
import io.inspector.security.ReadScopeGate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 (pure): the leak-views scope projection (S2, R-SAFE-17, issue #126). Unlike the
 * dashboard's error groups, every leak-view window count decomposes per engine, so a scoped
 * caller's totals are honestly RECOMPUTED from survivors — never nulled. A definition touching no
 * readable engine is dropped; one only partially in scope is flagged {@code partial}.
 */
class LeakViewScopeProjectorTest {

    private final ReadScopeGate gate = mock(ReadScopeGate.class);
    private final Authentication auth = mock(Authentication.class);
    private final LeakViewScopeProjector projector = new LeakViewScopeProjector(gate);

    private static final LeakWindows WINDOWS =
            new LeakWindows("2026-06-12T00:00:00Z", "2026-04-13T00:00:00Z", "2026-07-05T00:00:00Z");

    private static LeakDefinitionCount def(String key, Map<String, EngineLeakCount> byEngine) {
        long a30 = byEngine.values().stream()
                .mapToLong(EngineLeakCount::activeOver30d)
                .sum();
        long a90 = byEngine.values().stream()
                .mapToLong(EngineLeakCount::activeOver90d)
                .sum();
        long s7 = byEngine.values().stream()
                .mapToLong(EngineLeakCount::suspendedStartedOver7d)
                .sum();
        return new LeakDefinitionCount(key, a30, a90, s7, byEngine, false);
    }

    private LeakViewsResponse fleetResponse() {
        return new LeakViewsResponse(
                "2026-07-12T00:00:00Z",
                WINDOWS,
                List.of(
                        def("only-a", Map.of("engine-a", new EngineLeakCount(200, 40, 3))),
                        def(
                                "both",
                                Map.of(
                                        "engine-a", new EngineLeakCount(5, 0, 0),
                                        "engine-b", new EngineLeakCount(7, 0, 0))),
                        def("only-b", Map.of("engine-b", new EngineLeakCount(9, 0, 0)))),
                false,
                List.of("engine-c"));
    }

    @Test
    void enforcementOffReturnsTheFleetResponseUnchanged() {
        when(gate.readableEngineIds(auth)).thenReturn(null); // unrestricted

        LeakViewsResponse input = fleetResponse();
        assertThat(projector.project(input, auth)).isSameAs(input);
    }

    @Test
    void projectsToReadableEnginesAndRecomputesCountsHonestly() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a"));

        LeakViewsResponse scoped = projector.project(fleetResponse(), auth);

        // only-b is dropped (no readable engine); only-a and both survive.
        assertThat(scoped.definitions())
                .extracting(LeakDefinitionCount::definitionKey)
                .containsExactly("only-a", "both");

        LeakDefinitionCount onlyA = scoped.definitions().get(0);
        assertThat(onlyA.activeOver30d()).isEqualTo(200); // fully visible, unchanged
        assertThat(onlyA.partial()).isFalse();
        assertThat(onlyA.countsByEngine()).containsOnlyKeys("engine-a");

        LeakDefinitionCount both = scoped.definitions().get(1);
        assertThat(both.activeOver30d()).isEqualTo(5); // honestly recomputed from engine-a only, not 12
        assertThat(both.partial()).isTrue(); // also runs on engine-b, outside scope
        assertThat(both.countsByEngine()).containsOnlyKeys("engine-a");

        // engine-c named as unavailable in the fleet response is outside this caller's scope too —
        // never leaked (no engine-topology disclosure beyond what the caller can read).
        assertThat(scoped.unavailableEngines()).isEmpty();
    }

    @Test
    void reSortsByTheRecomputedScopedTotalsNotTheOriginalFleetOrder() {
        // Fleet order ranks "mostly-hidden" above "fully-visible" (101 > 50) because an UNREADABLE
        // engine dominates its fleet total — after scoping, "fully-visible" actually leaks worse
        // for THIS caller (50 > 1) and must sort first (Gemini review, issue #126).
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a"));
        LeakViewsResponse fleet = new LeakViewsResponse(
                "2026-07-12T00:00:00Z",
                WINDOWS,
                List.of(
                        def(
                                "mostly-hidden",
                                Map.of(
                                        "engine-a", new EngineLeakCount(1, 0, 0),
                                        "engine-b", new EngineLeakCount(100, 0, 0))),
                        def("fully-visible", Map.of("engine-a", new EngineLeakCount(50, 0, 0)))),
                false,
                List.of());

        LeakViewsResponse scoped = projector.project(fleet, auth);

        assertThat(scoped.definitions())
                .extracting(LeakDefinitionCount::definitionKey)
                .containsExactly("fully-visible", "mostly-hidden");
    }

    @Test
    void unavailableEnginesKeepsAReadableEngineButDropsAnUnreadableOne() {
        // Copilot review, issue #126: the existing coverage only exercised unavailableEngines
        // collapsing to empty — prove the readable-but-unavailable member survives too.
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a", "engine-c"));
        LeakViewsResponse fleet = new LeakViewsResponse(
                "2026-07-12T00:00:00Z",
                WINDOWS,
                List.of(def("only-a", Map.of("engine-a", new EngineLeakCount(1, 0, 0)))),
                true,
                List.of("engine-b", "engine-c"));

        LeakViewsResponse scoped = projector.project(fleet, auth);

        assertThat(scoped.unavailableEngines()).containsExactly("engine-c");
    }

    @Test
    void lowerBoundCarriesOverUnchangedRegardlessOfScope() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a"));
        LeakViewsResponse fleet = new LeakViewsResponse(
                "2026-07-12T00:00:00Z",
                WINDOWS,
                List.of(def("only-a", Map.of("engine-a", new EngineLeakCount(1, 0, 0)))),
                true, // some engine (possibly outside scope) was unreachable/truncated
                List.of("engine-b"));

        LeakViewsResponse scoped = projector.project(fleet, auth);

        assertThat(scoped.lowerBound()).isTrue(); // a fleet-wide honesty floor, not scope-dependent
        assertThat(scoped.unavailableEngines()).isEmpty(); // engine-b itself is outside scope, not named
    }

    @Test
    void aCallerWithNoReadableEnginesSeesNoDefinitions() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of());

        LeakViewsResponse scoped = projector.project(fleetResponse(), auth);

        assertThat(scoped.definitions()).isEmpty();
    }
}

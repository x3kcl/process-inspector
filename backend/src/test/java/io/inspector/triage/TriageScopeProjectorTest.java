package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.dto.EngineDto;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.security.ReadScopeGate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 (pure): the triage dashboard scope projection (S2, R-SAFE-17). A per-engine VIEWER must
 * see only their engines' health/counts/error-groups; the fleet roll-ups are re-summed from the
 * survivors, a group touching no readable engine is dropped, and a PARTIALLY-visible group keeps a
 * recomputed total + filtered per-engine counts but nulls the un-splittable DL/retrying split.
 */
class TriageScopeProjectorTest {

    private final ReadScopeGate gate = mock(ReadScopeGate.class);
    private final Authentication auth = mock(Authentication.class);
    private final TriageScopeProjector projector = new TriageScopeProjector(gate);

    private static EngineDto engine(String id) {
        return new EngineDto(
                id, id, "dev", null, "read-write", "active", null, true, null, null, null, null, null, null, null);
    }

    private static PerEngineTriage ok() {
        return new PerEngineTriage(true, null, null, null, false);
    }

    private static ErrorGroup group(String hash, long dl, long retrying, Map<String, Map<String, Long>> byEngine) {
        long total = byEngine.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToLong(Long::longValue)
                .sum();
        return new ErrorGroup(hash, 1, "Ex", "msg", "raw", total, dl, retrying, byEngine);
    }

    private TriageDashboardResponse fleetDashboard() {
        return new TriageDashboardResponse(
                "2026-07-12T00:00:00Z",
                List.of(engine("engine-a"), engine("engine-b")),
                Map.of("ACTIVE", 5L),
                Map.of("engine-a", Map.of("ACTIVE", 2L), "engine-b", Map.of("ACTIVE", 3L)),
                List.of(
                        group("only-a", 4, 0, Map.of("engine-a", Map.of("k:v1", 4L))),
                        group("both", 2, 1, Map.of("engine-a", Map.of("k:v1", 1L), "engine-b", Map.of("k:v1", 2L))),
                        group("only-b", 9, 0, Map.of("engine-b", Map.of("k:v1", 9L)))),
                Map.of("engine-a", ok(), "engine-b", ok()));
    }

    @Test
    void enforcementOffReturnsTheFleetDashboardUnchanged() {
        when(gate.readableEngineIds(auth)).thenReturn(null); // unrestricted
        TriageDashboardResponse input = fleetDashboard();
        // The exact same object is returned untouched — no projection work on the legacy path.
        assertThat(projector.project(input, auth)).isSameAs(input);
    }

    @Test
    void projectsToReadableEnginesAndRecomputesRollUps() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a"));

        TriageDashboardResponse scoped = projector.project(fleetDashboard(), auth);

        assertThat(scoped.engines()).extracting(EngineDto::id).containsExactly("engine-a");
        assertThat(scoped.statusCountsByEngine()).containsOnlyKeys("engine-a");
        assertThat(scoped.statusCounts()).containsEntry("ACTIVE", 2L); // re-summed from engine-a only, NOT 5
        assertThat(scoped.perEngine()).containsOnlyKeys("engine-a");

        // only-b is dropped (no readable engine); only-a and both survive
        assertThat(scoped.errorGroups()).extracting(ErrorGroup::signatureHash).containsExactly("only-a", "both");

        ErrorGroup onlyA = scoped.errorGroups().get(0);
        assertThat(onlyA.total()).isEqualTo(4);
        assertThat(onlyA.deadLetterCount()).isEqualTo(4L); // fully visible → split preserved

        ErrorGroup both = scoped.errorGroups().get(1);
        assertThat(both.total()).isEqualTo(1); // recomputed from engine-a's slice only (not 3)
        assertThat(both.countsByEngine()).containsOnlyKeys("engine-a");
        assertThat(both.deadLetterCount()).isNull(); // partial → un-splittable split nulled
        assertThat(both.retryingCount()).isNull();
    }

    @Test
    void aCallerWithNoReadableEnginesSeesAnEmptyDashboard() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of());

        TriageDashboardResponse scoped = projector.project(fleetDashboard(), auth);

        assertThat(scoped.engines()).isEmpty();
        assertThat(scoped.statusCounts()).isEmpty();
        assertThat(scoped.errorGroups()).isEmpty();
        assertThat(scoped.perEngine()).isEmpty();
    }
}

package io.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.client.FlowableEngineClient.CallPriority;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.triage.TriageAggregationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the flatten from the Stage-0 dashboard to per-lane observations. The honesty rules —
 * a down engine contributes no row; a NULL out-of-scope count (cannot discriminate) is never a
 * fabricated zero — are the whole point, so they are asserted explicitly.
 */
class PollingSnapshotSourceTest {

    private final TriageAggregationService aggregation = mock(TriageAggregationService.class);
    private final PollingSnapshotSource source = new PollingSnapshotSource(aggregation);

    @Test
    void flattensStatusLanesPerEngineAndRunsOnTheBackgroundLane() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("engine-a", Map.of("ACTIVE", 5L, "FAILED", 2L)),
                        Map.of("engine-a", new PerEngineTriage(true, null, "complete", 3, false))));

        List<EngineLaneCount> out = source.sample();

        verify(aggregation).aggregate(CallPriority.BACKGROUND);
        assertThat(out)
                .extracting(EngineLaneCount::engineId, EngineLaneCount::lane, EngineLaneCount::count)
                .containsExactlyInAnyOrder(
                        tuple("engine-a", SnapshotLane.ACTIVE, 5L),
                        tuple("engine-a", SnapshotLane.FAILED, 2L),
                        tuple("engine-a", SnapshotLane.OUT_OF_SCOPE_DLQ, 3L));
    }

    @Test
    void downEnginesContributeNoRows() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of(), // a down engine is absent from statusCountsByEngine
                        Map.of("engine-b", new PerEngineTriage(false, "connection refused", null, null, false))));

        assertThat(source.sample()).isEmpty();
    }

    @Test
    void nullOutOfScopeCountIsNeverWrittenAsZero() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("legacy", Map.of("ACTIVE", 1L)),
                        Map.of("legacy", new PerEngineTriage(true, null, "complete", null, false))));

        assertThat(source.sample())
                .extracting(EngineLaneCount::lane)
                .containsExactly(SnapshotLane.ACTIVE)
                .doesNotContain(SnapshotLane.OUT_OF_SCOPE_DLQ);
    }

    private static TriageDashboardResponse dashboard(
            Map<String, Map<String, Long>> statusCountsByEngine, Map<String, PerEngineTriage> perEngine) {
        return new TriageDashboardResponse(
                "2026-07-08T12:00:00Z", List.of(), Map.of(), statusCountsByEngine, List.of(), perEngine);
    }
}

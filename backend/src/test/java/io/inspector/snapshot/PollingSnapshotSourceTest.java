package io.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.triage.TriageAggregationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the flatten from the Stage-0 dashboard to one {@link AggregationSample}. The honesty
 * rules — a down engine contributes no row; a NULL out-of-scope count (cannot discriminate) is
 * never a fabricated zero; a capped failure scan marks its engine truncated — are the whole
 * point, so they are asserted explicitly.
 */
class PollingSnapshotSourceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:37Z");

    private final TriageAggregationService aggregation = mock(TriageAggregationService.class);
    private final PollingSnapshotSource source =
            new PollingSnapshotSource(aggregation, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void flattensStatusLanesPerEngineAndRunsOnTheBackgroundLane() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("engine-a", Map.of("ACTIVE", 5L, "FAILED", 2L)),
                        List.of(),
                        Map.of("engine-a", new PerEngineTriage(true, null, "complete", 3, false))));

        AggregationSample out = source.sample();

        verify(aggregation).aggregate(CallPriority.BACKGROUND);
        assertThat(out.laneCounts())
                .extracting(EngineLaneCount::engineId, EngineLaneCount::lane, EngineLaneCount::count)
                .containsExactlyInAnyOrder(
                        tuple("engine-a", SnapshotLane.ACTIVE, 5L),
                        tuple("engine-a", SnapshotLane.FAILED, 2L),
                        tuple("engine-a", SnapshotLane.OUT_OF_SCOPE_DLQ, 3L));
        assertThat(out.sampledAt()).isEqualTo(NOW);
    }

    @Test
    void downEnginesContributeNoRows() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of(), // a down engine is absent from statusCountsByEngine
                        List.of(),
                        Map.of("engine-b", new PerEngineTriage(false, "connection refused", null, null, false))));

        assertThat(source.sample().laneCounts()).isEmpty();
    }

    @Test
    void nullOutOfScopeCountIsNeverWrittenAsZero() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("legacy", Map.of("ACTIVE", 1L)),
                        List.of(),
                        Map.of("legacy", new PerEngineTriage(true, null, "complete", null, false))));

        assertThat(source.sample().laneCounts())
                .extracting(EngineLaneCount::lane)
                .containsExactly(SnapshotLane.ACTIVE)
                .doesNotContain(SnapshotLane.OUT_OF_SCOPE_DLQ);
    }

    @Test
    void carriesTheAggregationSideErrorGroupsThrough() {
        ErrorGroup group = new ErrorGroup(
                "hash-1",
                1,
                "java.net.SocketTimeoutException",
                "timeout after # ms",
                "timeout after 5000 ms",
                7,
                5,
                2,
                Map.of("engine-a", Map.of("order:v3", 7L)));
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("engine-a", Map.of("ACTIVE", 1L)),
                        List.of(group),
                        Map.of("engine-a", new PerEngineTriage(true, null, "complete", null, false))));

        assertThat(source.sample().errorGroups()).containsExactly(group);
    }

    @Test
    void cappedFailureScansMarkTheirEngineTruncated() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of(
                                "engine-a", Map.of("ACTIVE", 1L),
                                "engine-b", Map.of("ACTIVE", 1L)),
                        List.of(),
                        Map.of(
                                "engine-a", new PerEngineTriage(true, null, "truncated@500", null, false),
                                "engine-b", new PerEngineTriage(true, null, "complete", null, false))));

        assertThat(source.sample().truncatedEngineIds()).containsExactly("engine-a");
    }

    private static TriageDashboardResponse dashboard(
            Map<String, Map<String, Long>> statusCountsByEngine,
            List<ErrorGroup> errorGroups,
            Map<String, PerEngineTriage> perEngine) {
        return new TriageDashboardResponse(
                "2026-07-08T12:00:00Z", List.of(), Map.of(), statusCountsByEngine, errorGroups, perEngine);
    }
}

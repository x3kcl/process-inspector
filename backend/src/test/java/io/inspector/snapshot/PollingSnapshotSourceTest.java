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
import java.util.LinkedHashMap;
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

    /* ---------------- #302: cycleComplete — the blind-cycle honesty marker ---------------- */

    @Test
    void cycleIsCompleteWhenEveryRegistryEngineCameBackOk() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of(
                                "engine-a", Map.of("ACTIVE", 1L),
                                "engine-b", Map.of("ACTIVE", 1L)),
                        List.of(),
                        Map.of(
                                "engine-a", new PerEngineTriage(true, null, "complete", null, false),
                                "engine-b", new PerEngineTriage(true, null, "complete", null, false))));

        assertThat(source.sample().cycleComplete()).isTrue();
    }

    @Test
    void cycleIsIncompleteWhenAnyRegistryEngineEnvelopeFailed() {
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("engine-a", Map.of("ACTIVE", 1L)), // engine-b absent — it's down
                        List.of(),
                        Map.of(
                                "engine-a", new PerEngineTriage(true, null, "complete", null, false),
                                "engine-b", new PerEngineTriage(false, "connection refused", null, null, false))));

        assertThat(source.sample().cycleComplete())
                .as("one unreachable registry engine makes the whole cycle blind (#302)")
                .isFalse();
    }

    @Test
    void aTruncatedButReachableEngineStillCountsAsCycleComplete() {
        // truncation (scan cap hit) is a lower-bound honesty concern, NOT unreachability — the
        // engine answered, so the cycle observed it. cycleComplete and truncatedEngineIds are
        // orthogonal flags.
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("engine-a", Map.of("ACTIVE", 1L)),
                        List.of(),
                        Map.of("engine-a", new PerEngineTriage(true, null, "truncated@500", null, false))));

        assertThat(source.sample().cycleComplete()).isTrue();
        assertThat(source.sample().truncatedEngineIds()).containsExactly("engine-a");
    }

    /* ---------------- #372: fleetEngineIds — the pass's observation SCOPE ---------------- */

    @Test
    void theFleetIsTheWholeEnvelopeKeySetIncludingAnEngineThatAnsweredNotOk() {
        // Scope is the INTENT set: an engine that was fanned out to but failed IS in scope, and
        // saying so is cycleComplete's job, not fleet's. Conflating the two would make an outage
        // look like a composition change and void everything twice over.
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("engine-a", Map.of("ACTIVE", 1L)),
                        List.of(),
                        Map.of(
                                "engine-a", new PerEngineTriage(true, null, "complete", null, false),
                                "engine-b", new PerEngineTriage(false, "connection refused", null, null, false))));

        AggregationSample out = source.sample();

        assertThat(out.fleetEngineIds()).containsExactlyInAnyOrder("engine-a", "engine-b");
        assertThat(out.canonicalFleet()).isEqualTo("engine-a,engine-b");
        assertThat(out.cycleComplete()).isFalse(); // the two markers stay orthogonal
    }

    @Test
    void theCanonicalFleetStringIsStableUnderARegistryREORDER() {
        // perEngine is registry-ORDERED, and moving an engine up the YAML list is not a
        // composition change. Without the canonical sort every such edit would read as a new fleet
        // and needlessly void every delta and every spell at the boundary.
        Map<String, PerEngineTriage> forward = new LinkedHashMap<>();
        forward.put("engine-a", new PerEngineTriage(true, null, "complete", null, false));
        forward.put("engine-b", new PerEngineTriage(true, null, "complete", null, false));
        forward.put("engine-7", new PerEngineTriage(true, null, "complete", null, false));
        Map<String, PerEngineTriage> reordered = new LinkedHashMap<>();
        reordered.put("engine-7", new PerEngineTriage(true, null, "complete", null, false));
        reordered.put("engine-b", new PerEngineTriage(true, null, "complete", null, false));
        reordered.put("engine-a", new PerEngineTriage(true, null, "complete", null, false));

        when(aggregation.aggregate(CallPriority.BACKGROUND)).thenReturn(dashboard(Map.of(), List.of(), forward));
        String first = source.sample().canonicalFleet();
        when(aggregation.aggregate(CallPriority.BACKGROUND)).thenReturn(dashboard(Map.of(), List.of(), reordered));
        String second = source.sample().canonicalFleet();

        assertThat(first).isEqualTo("engine-7,engine-a,engine-b");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void aDisabledEngineSimplyLeavesTheFleetWhileTheCycleStaysComplete() {
        // The #372 defect in one assertion: a registry DISABLE never enters perEngine at all, so
        // the pass is honestly complete for its now-smaller scope. Only `fleet` records the shrink.
        when(aggregation.aggregate(CallPriority.BACKGROUND))
                .thenReturn(dashboard(
                        Map.of("engine-a", Map.of("ACTIVE", 1L)),
                        List.of(),
                        Map.of("engine-a", new PerEngineTriage(true, null, "complete", null, false))));

        AggregationSample out = source.sample();

        assertThat(out.cycleComplete()).isTrue();
        assertThat(out.canonicalFleet()).isEqualTo("engine-a");
    }

    @Test
    void anEnabledFleetOfZeroEnginesIsBlindNotVacuouslyComplete() {
        // #380 (the adjacent case): with NO enabled engines `perEngine` is empty, so the
        // "did everyone answer?" loop never executes and the flag stayed vacuously TRUE — a pass
        // that observed NOBODY claiming to be a complete observation of everybody. That is not
        // "the fleet answered", it is "there was no fleet"; the honest reading is a BLIND cycle.
        // #302's meaning for an UNREACHABLE engine is untouched — this is the empty-scope case,
        // which #302 never had an opinion about.
        when(aggregation.aggregate(CallPriority.BACKGROUND)).thenReturn(dashboard(Map.of(), List.of(), Map.of()));

        AggregationSample out = source.sample();

        assertThat(out.fleetEngineIds()).isEmpty();
        assertThat(out.canonicalFleet()).isEmpty();
        assertThat(out.cycleComplete())
                .as("a fleet-empty cycle observed nothing and must not claim to be complete (#380)")
                .isFalse();
    }

    private static TriageDashboardResponse dashboard(
            Map<String, Map<String, Long>> statusCountsByEngine,
            List<ErrorGroup> errorGroups,
            Map<String, PerEngineTriage> perEngine) {
        return new TriageDashboardResponse(
                "2026-07-08T12:00:00Z", List.of(), Map.of(), statusCountsByEngine, errorGroups, perEngine);
    }
}

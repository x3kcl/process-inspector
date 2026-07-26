package io.inspector.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
import io.inspector.dto.ResolveResponse;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rung 2 (mock the engine client): S2 read scoping (R-SAFE-17, issue #300). The load-bearing
 * property — proven with {@code verifyNoInteractions}/argument-scoped {@code never()} — is that
 * an engine outside the caller's read scope is NEVER fanned out to. These tests run with
 * enforcement effectively ON (a non-null {@code readableEngineIds}); the pre-existing
 * {@link ResolveServiceTest} runs with it OFF ({@code null}) and proves nothing about scoping.
 *
 * <p>Doctrine mirrored from {@link io.inspector.aggregate.SearchServiceScopeTest} /
 * {@link io.inspector.aggregate.PersonTaskSearchServiceTest}: an explicit {@code engine:id}
 * composite naming an out-of-scope engine is LABELED (never silently dropped); an implicit
 * "all engines" query narrows to the readable set SILENTLY (no perEngine entry at all for the
 * excluded engine — labeling it would leak its existence to a caller who can't see it).
 */
class ResolveServiceScopeTest {

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final CmmnApiClient cmmnFlowable = mock(CmmnApiClient.class);
    private final InstanceDetailService detail = mock(InstanceDetailService.class);
    private final ResolveService service = new ResolveService(registry, flowable, cmmnFlowable, detail);

    private final EngineConfig engineA = TestEngines.engineInTenant("engine-a", "http://a", "tenant-a");
    private final EngineConfig engineB = TestEngines.engineInTenant("engine-b", "http://b", "tenant-b");

    /** Every BPMN resolution leg misses on every engine — same style as {@link ResolveServiceTest}. */
    private void noEngineMatchesAnything() {
        when(flowable.getHistoricProcessInstance(any(), any(), any())).thenReturn(null);
        when(flowable.getExecution(any(), any(), any())).thenReturn(null);
        when(flowable.getTask(any(), any(), any())).thenReturn(null);
        when(flowable.getHistoricTaskInstance(any(), any(), any())).thenReturn(null);
        when(flowable.getJob(any(), any(), any(), any())).thenReturn(null);
        when(flowable.queryHistoricProcessInstances(any(), any(), any()))
                .thenReturn(new FlowablePage(List.of(), 0, 0, 0));
    }

    @Test
    void anExplicitOutOfScopeEngineCompositeIsLabeledAndNeverQueried() {
        when(registry.all()).thenReturn(List.of(engineA, engineB));

        // Composite "engine-b:PROC1" names engine-b explicitly; the caller may only read engine-a.
        // Zero calls of ANY kind may fire — the security-critical assertion — and engine-b must be
        // labeled outside scope rather than silently absent or misreported as unreachable.
        ResolveResponse response = service.resolve("engine-b:PROC1", Set.of("engine-a"));

        verifyNoInteractions(flowable);
        verifyNoInteractions(cmmnFlowable);
        assertThat(response.matches()).isEmpty();
        assertThat(response.perEngine()).containsOnlyKeys("engine-b");
        assertThat(response.perEngine().get("engine-b").ok()).isFalse();
        assertThat(response.perEngine().get("engine-b").outOfScope()).isTrue();
        assertThat(response.perEngine().get("engine-b").error()).contains("outside your access scope");
    }

    @Test
    void anImplicitFanOutNarrowsSilentlyToTheReadableSet() {
        when(registry.all()).thenReturn(List.of(engineA, engineB));
        noEngineMatchesAnything();

        // A plain (non-composite) query fans out over "all engines" implicitly; the caller may
        // only read engine-a, so engine-b must be excluded WITHOUT a labeled perEngine entry —
        // labeling it would leak its existence to a caller outside its scope (R-SAFE-17 asymmetry).
        ResolveResponse response = service.resolve("PROC1", Set.of("engine-a"));

        verify(flowable, never()).getHistoricProcessInstance(eq(engineB), any(), any());
        verify(flowable, never()).getExecution(eq(engineB), any(), any());
        verify(flowable, never()).getTask(eq(engineB), any(), any());
        verify(flowable, never()).getHistoricTaskInstance(eq(engineB), any(), any());
        verify(flowable, never()).getJob(eq(engineB), any(), any(), any());
        verify(flowable, never()).queryHistoricProcessInstances(eq(engineB), any(), any());
        assertThat(response.perEngine()).doesNotContainKey("engine-b");
        // engine-a WAS fanned out to (the readable one) — reached, not scope-excluded.
        assertThat(response.perEngine().get("engine-a").ok()).isTrue();
        assertThat(response.perEngine().get("engine-a").outOfScope()).isFalse();
    }

    @Test
    void unrestrictedNullScopeFansOutToEveryEngineAndLabelsNothingOutOfScope() {
        when(registry.all()).thenReturn(List.of(engineA, engineB));
        noEngineMatchesAnything();

        // null = enforcement off (or a global grant): both engines are fanned out, neither is
        // scope-excluded — the additive-narrowing contract must be a pure no-op here.
        ResolveResponse response = service.resolve("PROC1", null);

        assertThat(response.perEngine()).containsOnlyKeys("engine-a", "engine-b");
        assertThat(response.perEngine().values()).allMatch(p -> p.ok() && !p.outOfScope());
    }
}

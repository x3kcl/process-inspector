package io.inspector.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
import io.inspector.dto.ResolveResponse;
import io.inspector.dto.ResolveResponse.MatchKind;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The omnibox resolver's CMMN leg (usability fix, Finding #1): a pasted Case id must be answered
 * TRUTHFULLY — the case really exists on a co-deployed CMMN engine, so "not found on any reachable
 * engine" is a lie (do-no-harm / never-lie iron rule). These tests drive one engine with every
 * BPMN step missing, proving the CMMN case is claimed as a read-only, non-navigable match — and
 * ONLY on an engine that can discriminate scope (≥ 6.8), never a guess on an older one.
 */
class ResolveServiceTest {

    private static final String ENGINE = "engine-a";
    private static final String CASE_ID = "cbfc23c3-7abe-11f1-b839-3210ba03f0d0";

    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private final FlowableEngineClient flowable = mock(FlowableEngineClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final InstanceDetailService detail = mock(InstanceDetailService.class);
    private final ResolveService service = new ResolveService(registry, flowable, detail);

    private void engineWith(EngineCapabilities capabilities) {
        when(registry.all()).thenReturn(List.of(engine));
        when(registry.healthOf(ENGINE))
                .thenReturn(new EngineHealth(true, "?", null, 0L, capabilities, null, null, null));
        // Every BPMN resolution step misses — a CMMN case id belongs to none of these tables.
        when(flowable.getHistoricProcessInstance(any(), any())).thenReturn(null);
        when(flowable.getExecution(any(), any())).thenReturn(null);
        when(flowable.getTask(any(), any())).thenReturn(null);
        when(flowable.getHistoricTaskInstance(any(), any())).thenReturn(null);
        when(flowable.getJob(any(), any(), any())).thenReturn(null);
        when(flowable.queryHistoricProcessInstances(any(), any())).thenReturn(new FlowablePage(List.of(), 0, 0, 0));
    }

    @Test
    void aCmmnCaseIdResolvesTruthfullyAsAReadOnlyNonNavigableMatch() {
        engineWith(scopeTypeCapable());
        Map<String, Object> runningCase = new HashMap<>();
        runningCase.put("id", CASE_ID);
        runningCase.put("caseDefinitionId", "def-uuid");
        runningCase.put("startTime", "2026-07-08T11:19:00.000Z");
        when(flowable.getCmmnCaseInstance(engine, CASE_ID)).thenReturn(runningCase);
        when(flowable.getCmmnCaseDefinition(engine, "def-uuid"))
                .thenReturn(def("demoFailingCase", "Demo failing case"));

        ResolveResponse response = service.resolve(CASE_ID);

        assertThat(response.matches()).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(MatchKind.CMMN_CASE);
            assertThat(m.engineId()).isEqualTo(ENGINE);
            assertThat(m.matchedId()).isEqualTo(CASE_ID);
            assertThat(m.definitionKey()).isEqualTo("demoFailingCase");
            // Non-navigable: no process instance, no composite deep-link route.
            assertThat(m.processInstanceId()).isNull();
            assertThat(m.compositeId()).isNull();
        });
        // The engine WAS reached and the paste WAS found — no false "not found on any engine".
        assertThat(response.perEngine().get(ENGINE).ok()).isTrue();
    }

    @Test
    void anEndedCmmnCaseIsFoundViaHistoryWhenNotRunning() {
        engineWith(scopeTypeCapable());
        when(flowable.getCmmnCaseInstance(engine, CASE_ID)).thenReturn(null); // not running
        Map<String, Object> endedCase = new HashMap<>();
        endedCase.put("id", CASE_ID);
        endedCase.put("caseDefinitionKey", "demoFailingCase"); // historic DTO carries the key
        endedCase.put("endTime", "2026-07-08T12:00:00.000Z");
        when(flowable.getHistoricCmmnCaseInstance(engine, CASE_ID)).thenReturn(endedCase);

        ResolveResponse response = service.resolve(CASE_ID);

        assertThat(response.matches()).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(MatchKind.CMMN_CASE);
            assertThat(m.definitionKey()).isEqualTo("demoFailingCase");
        });
    }

    @Test
    void aPreSixEightEngineNeverClaimsACmmnCaseAndStaysHonestlyNotFound() {
        // scopeType absent — an older engine whose cmmn context is dead-letter-blind (spike Q3).
        engineWith(new EngineCapabilities(true, true, false, false, true));

        ResolveResponse response = service.resolve(CASE_ID);

        assertThat(response.matches()).isEmpty(); // honest "not found on any reachable engine"
        assertThat(response.perEngine().get(ENGINE).ok()).isTrue();
        // Never probed the cmmn context blind on an engine that can't discriminate scope.
        verify(flowable, never()).getCmmnCaseInstance(any(), any());
        verify(flowable, never()).getHistoricCmmnCaseInstance(any(), any());
    }

    private static EngineCapabilities scopeTypeCapable() {
        return new EngineCapabilities(true, true, true, true, true);
    }

    private static Map<String, Object> def(String key, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("name", name);
        return m;
    }
}

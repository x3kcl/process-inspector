package io.inspector.cmmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.CaseDiagram;
import io.inspector.dto.CasePlanItems;
import io.inspector.dto.CasePlanItems.CasePlanItem;
import io.inspector.dto.CmmnLiveJobState;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the CMMN case-detail service's pure join/mapping logic and its
 * 6.8+ CAPABILITY GATE (Case Inspector Phase 2). The load-bearing wire trap is Q7 — a dead-letter
 * job's {@code elementId} is the plan-item DEFINITION id, NOT the plan item's own {@code elementId}
 * (the CMMN DI shape key), so the FAILED join MUST key on {@code planItemInstanceId} == the plan
 * item's {@code id}. The read-over-real-wire proof is the rung-4 {@code CaseDetailIT}; here the
 * client is mocked to isolate the join and gate ordering.
 */
class CaseDetailServiceTest {

    private static final String ENGINE = "e";

    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private final FlowableEngineClient flowable = mock(FlowableEngineClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final CaseDetailService service = new CaseDetailService(registry, flowable);

    private void health(EngineCapabilities capabilities) {
        when(registry.require(ENGINE)).thenReturn(engine);
        when(registry.healthOf(ENGINE))
                .thenReturn(new EngineHealth(true, "?", null, 0L, capabilities, null, null, null));
    }

    private static EngineCapabilities scopeTypeCapable() {
        return new EngineCapabilities(true, true, true, true, true);
    }

    /* -------------------- capability gate -------------------- */

    @Test
    void unprobedEngineIsRefusedBeforeAnyCall() {
        health(null);
        assertThatThrownBy(() -> service.vitals(ENGINE, "case-1"))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unknown"));
        verify(flowable, never()).getHistoricCmmnCaseInstance(any(), any());
        verify(flowable, never()).listCmmnPlanItemInstances(any(), any(), anyInt(), anyInt());
    }

    @Test
    void preSixEightEngineIsRefusedOnEveryLeg() {
        health(new EngineCapabilities(true, true, false, false, true)); // scopeType absent
        assertThatThrownBy(() -> service.planItems(ENGINE, "case-1"))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unavailable"));
        assertThatThrownBy(() -> service.diagram(ENGINE, "case-1")).isInstanceOf(GuardRefusedException.class);
        verify(flowable, never()).cmmnDeploymentResourceData(any(), any(), any());
    }

    /* -------------------- Q7: the FAILED join keys on planItemInstanceId, not elementId -------- */

    @Test
    void failedJoinKeysOnPlanItemInstanceIdNotElementId() {
        // The dead-letter job's elementId is the plan-item DEFINITION id ("failingService");
        // the plan item's own elementId is the DI shape key ("planItem_svc"). A naive elementId
        // join would never match. The correct join is job.planItemInstanceId == planItem.id.
        Map<String, Object> job = new HashMap<>();
        job.put("planItemInstanceId", "pi-1");
        job.put("elementId", "failingService"); // the trap: definition id, not the shape key
        when(flowable.listCmmnDeadLetterJobs(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(job), 1, 0, 500));
        when(flowable.listCmmnJobs(any(), any(), anyInt(), anyInt())).thenReturn(FlowablePage.empty());

        Map<String, CmmnLiveJobState> byPlanItem = service.liveJobStates(engine, "case-1");

        assertThat(byPlanItem).containsEntry("pi-1", CmmnLiveJobState.FAILED);
        assertThat(byPlanItem).doesNotContainKey("failingService");
    }

    @Test
    void failedTakesPrecedenceOverRetrying() {
        // A plan item could momentarily have both a live job and a dead-letter row; FAILED wins.
        Map<String, Object> retrying = new HashMap<>();
        retrying.put("planItemInstanceId", "pi-1");
        Map<String, Object> failed = new HashMap<>();
        failed.put("planItemInstanceId", "pi-1");
        when(flowable.listCmmnJobs(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(retrying), 1, 0, 500));
        when(flowable.listCmmnDeadLetterJobs(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(failed), 1, 0, 500));

        assertThat(service.liveJobStates(engine, "case-1")).containsEntry("pi-1", CmmnLiveJobState.FAILED);
    }

    @Test
    void blankPlanItemInstanceIdIsIgnored() {
        Map<String, Object> job = new HashMap<>();
        job.put("planItemInstanceId", "  ");
        when(flowable.listCmmnJobs(any(), any(), anyInt(), anyInt())).thenReturn(FlowablePage.empty());
        when(flowable.listCmmnDeadLetterJobs(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(job), 1, 0, 500));
        assertThat(service.liveJobStates(engine, "case-1")).isEmpty();
    }

    @Test
    void diagramMarkersUseThePlanItemElementIdNotTheJobElementId() {
        health(scopeTypeCapable());
        // definition + resource for the diagram fetch
        Map<String, Object> definition = new HashMap<>();
        definition.put("deploymentId", "dep-1");
        definition.put("resource", "http://x/cmmn-api/cmmn-repository/deployments/dep-1/resources/demo.cmmn.xml");
        definition.put("graphicalNotationDefined", true);
        Map<String, Object> caseRow = new HashMap<>();
        caseRow.put("caseDefinitionId", "def-uuid");
        when(flowable.getHistoricCmmnCaseInstance(any(), eq("case-1"))).thenReturn(caseRow);
        when(flowable.getCmmnCaseDefinition(any(), eq("def-uuid"))).thenReturn(definition);
        when(flowable.cmmnDeploymentResourceData(any(), eq("dep-1"), eq("demo.cmmn.xml")))
                .thenReturn("<definitions/>");

        // one runtime plan item ("planItem_svc") holding a dead-letter job whose elementId differs
        Map<String, Object> planItem = new HashMap<>();
        planItem.put("id", "pi-1");
        planItem.put("elementId", "planItem_svc");
        planItem.put("state", "async-active");
        when(flowable.listCmmnPlanItemInstances(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(planItem), 1, 0, 100));
        Map<String, Object> job = new HashMap<>();
        job.put("planItemInstanceId", "pi-1");
        job.put("elementId", "failingService");
        when(flowable.listCmmnDeadLetterJobs(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(job), 1, 0, 500));
        when(flowable.listCmmnJobs(any(), any(), anyInt(), anyInt())).thenReturn(FlowablePage.empty());

        CaseDiagram diagram = service.diagram(ENGINE, "case-1");

        assertThat(diagram.graphicalNotationDefined()).isTrue();
        assertThat(diagram.failedPlanItemElementIds()).containsExactly("planItem_svc");
        assertThat(diagram.failedPlanItemElementIds()).doesNotContain("failingService");
        assertThat(diagram.activePlanItemElementIds()).containsExactly("planItem_svc"); // async-active
    }

    /* -------------------- ended case: runtime-only timeline (Q6) -------------------- */

    @Test
    void endedCaseTimelineIsUnavailableNotAFabricatedEmptyList() {
        health(scopeTypeCapable());
        Map<String, Object> ended = new HashMap<>();
        ended.put("endTime", "2026-07-08T09:00:00.000+00:00");
        ended.put("state", "completed");
        when(flowable.getHistoricCmmnCaseInstance(any(), eq("case-1"))).thenReturn(ended);
        when(flowable.getCmmnCaseInstance(any(), eq("case-1"))).thenReturn(null);

        CasePlanItems planItems = service.planItems(ENGINE, "case-1");

        assertThat(planItems.available()).isFalse();
        assertThat(planItems.unavailableReason()).contains("running cases only");
        assertThat(planItems.planItems()).isEmpty();
        // no plan-item scan is even attempted for an ended case
        verify(flowable, never()).listCmmnPlanItemInstances(any(), any(), anyInt(), anyInt());
    }

    /* -------------------- pure static helpers -------------------- */

    @Test
    void normalizesStateWithoutASuspendedLane() {
        assertThat(CaseDetailService.normalizeState(Map.of(), null, false)).isEqualTo("ACTIVE");
        assertThat(CaseDetailService.normalizeState(null, Map.of("state", "completed"), true))
                .isEqualTo("COMPLETED");
        assertThat(CaseDetailService.normalizeState(null, Map.of("state", "terminated"), true))
                .isEqualTo("TERMINATED");
        // no state field on the historic row → fall back to endTime presence
        assertThat(CaseDetailService.normalizeState(null, new HashMap<>(), true))
                .isEqualTo("COMPLETED");
    }

    @Test
    void isActiveStateHighlightsInPlayItemsOnly() {
        assertThat(CaseDetailService.isActiveState("active")).isTrue();
        assertThat(CaseDetailService.isActiveState("async-active")).isTrue();
        assertThat(CaseDetailService.isActiveState("enabled")).isTrue();
        assertThat(CaseDetailService.isActiveState("completed")).isFalse();
        assertThat(CaseDetailService.isActiveState("terminated")).isFalse();
        assertThat(CaseDetailService.isActiveState(null)).isFalse();
    }

    @Test
    void resourceNameParsesTheUrlTailAndDecodes() {
        assertThat(CaseDetailService.resourceNameOf(Map.of("resourceName", "a.cmmn.xml")))
                .isEqualTo("a.cmmn.xml");
        assertThat(CaseDetailService.resourceNameOf(
                        Map.of("resource", "http://x/cmmn-repository/deployments/d/resources/demo%20case.cmmn.xml")))
                .isEqualTo("demo case.cmmn.xml");
        assertThat(CaseDetailService.resourceNameOf(new HashMap<>())).isNull();
    }

    @Test
    void mapsPlanItemRowFaithfullyWithJoinedState() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "pi-1");
        row.put("elementId", "planItem_svc");
        row.put("name", "Failing service");
        row.put("planItemDefinitionType", "servicetask");
        row.put("state", "async-active");
        row.put("stage", false);
        row.put("createTime", "2026-07-08T12:44:41.634Z");

        CasePlanItem item = CaseDetailService.mapPlanItem(row, CmmnLiveJobState.FAILED);

        assertThat(item.id()).isEqualTo("pi-1");
        assertThat(item.elementId()).isEqualTo("planItem_svc");
        assertThat(item.planItemDefinitionType()).isEqualTo("servicetask");
        assertThat(item.state()).isEqualTo("async-active");
        assertThat(item.stage()).isFalse();
        assertThat(item.liveJobState()).isEqualTo(CmmnLiveJobState.FAILED);
        assertThat(item.completedTime()).isNull();
    }
}

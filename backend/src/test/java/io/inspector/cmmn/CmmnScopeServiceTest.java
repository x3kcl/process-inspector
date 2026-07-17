package io.inspector.cmmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.CmmnDeadLetterJob;
import io.inspector.dto.CmmnScopeFacet;
import io.inspector.dto.OutOfScopeDeadLetters;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Rung 1 (unit-test-patterns): the CMMN scope drill's discriminator, row mapping, and the
 * 6.8+ CAPABILITY GATE. The BFF is the real gate — a pre-6.8 engine (whose cmmn context is
 * dead-letter-blind, spike Q3) is refused with a ProblemDetail before any call leaves, never
 * a silently wrong view. The read-over-real-wire proof (enumerate the seeded failing case) is
 * the rung-4 {@code TriageCmmnScopeIT}; here the client is mocked to prove gate ordering.
 */
class CmmnScopeServiceTest {

    private static final String ENGINE = "e";

    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private final CmmnApiClient flowable = mock(CmmnApiClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final CmmnScopeService service = new CmmnScopeService(registry, flowable);

    private void health(EngineCapabilities capabilities) {
        when(registry.resolveOrNotFound(ENGINE)).thenReturn(engine);
        when(registry.healthOf(ENGINE))
                .thenReturn(new EngineHealth(true, "?", null, 0L, capabilities, null, null, null));
    }

    @Test
    void unprobedEngineIsRefusedNotSentBlind() {
        health(null); // no successful probe yet → capabilities unknown

        assertThatThrownBy(() -> service.outOfScopeDeadLetters(ENGINE))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unknown"));
        verify(flowable, never()).listCmmnDeadLetterJobs(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void preSixEightEngineIsRefusedWithUnsupportedVersion() {
        // changeState present, scopeType absent — a 6.5/6.6-era engine (cmmn context is DLQ-blind).
        health(new EngineCapabilities(true, true, false, false, true));

        assertThatThrownBy(() -> service.outOfScopeDeadLetters(ENGINE))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unavailable"));
        verify(flowable, never()).listCmmnDeadLetterJobs(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void discriminatorKeepsOnlyCaseScopedRows() {
        // The cmmn-api DLQ list also projects BPMN dead-letters (shared table) with a null case
        // attribution; only a non-null caseInstanceId marks a CMMN-scoped row (spike Q1).
        assertThat(CmmnScopeService.isCmmnScoped(row("caseInstanceId", "c-1"))).isTrue();
        assertThat(CmmnScopeService.isCmmnScoped(row("caseInstanceId", null))).isFalse();
        assertThat(CmmnScopeService.isCmmnScoped(row("caseInstanceId", "  "))).isFalse();
        assertThat(CmmnScopeService.isCmmnScoped(new HashMap<>())).isFalse();
    }

    @Test
    void mapsWireRowFaithfullyWithResolvedDefinition() {
        Map<String, Object> wire = new HashMap<>();
        wire.put("id", "job-1");
        wire.put("caseInstanceId", "case-1");
        wire.put("caseDefinitionId", "def-uuid");
        wire.put("planItemInstanceId", "pi-1");
        wire.put("elementId", "failingService");
        wire.put("elementName", "Failing service");
        wire.put("retries", 0);
        wire.put("exceptionMessage", "Unknown property used in expression: ${nonExistentBean.doStuff()}");
        wire.put("createTime", "2026-07-08T08:16:18.882+00:00");

        CmmnDeadLetterJob job = CmmnScopeService.map(
                wire, new CmmnScopeService.CaseDefinitionRef("demoFailingCase", "Demo failing case"));

        assertThat(job.id()).isEqualTo("job-1");
        assertThat(job.caseInstanceId()).isEqualTo("case-1");
        assertThat(job.caseDefinitionId()).isEqualTo("def-uuid");
        assertThat(job.caseDefinitionKey()).isEqualTo("demoFailingCase");
        assertThat(job.caseDefinitionName()).isEqualTo("Demo failing case");
        assertThat(job.planItemInstanceId()).isEqualTo("pi-1");
        assertThat(job.elementName()).isEqualTo("Failing service");
        assertThat(job.retries()).isZero();
        assertThat(job.exceptionMessage()).contains("nonExistentBean");
        assertThat(job.dueDate()).isNull(); // absent on the wire → null, not ""
    }

    @Test
    void resolvesDistinctDefinitionsOnceAndKeepsBpmnRowsOut() {
        health(scopeTypeCapable());
        // Two CMMN rows share one definition, a third has another; a BPMN row (null case) leaks in.
        Map<String, Object> rowA1 = cmmnRow("j1", "case-1", "def-A");
        Map<String, Object> rowA2 = cmmnRow("j2", "case-2", "def-A");
        Map<String, Object> rowB = cmmnRow("j3", "case-3", "def-B");
        Map<String, Object> bpmn = cmmnRow("j4", null, "proc-def"); // null caseInstanceId → excluded
        when(flowable.listCmmnDeadLetterJobs(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(rowA1, rowA2, rowB, bpmn), 4, 0, 50));
        when(flowable.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, "def-A"))
                .thenReturn(def("kA", "Case A"));
        when(flowable.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, "def-B"))
                .thenReturn(def("kB", "Case B"));

        OutOfScopeDeadLetters result = service.outOfScopeDeadLetters(ENGINE);

        assertThat(result.jobs()).extracting(CmmnDeadLetterJob::id).containsExactly("j1", "j2", "j3");
        assertThat(result.jobs())
                .extracting(CmmnDeadLetterJob::caseDefinitionName)
                .containsExactly("Case A", "Case A", "Case B");
        assertThat(result.scanned()).isEqualTo(4); // BPMN row was scanned but not enumerated
        // N+1 on DISTINCT definitions, never on jobs: def-A resolved once despite two rows.
        verify(flowable, times(1)).getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, "def-A");
        verify(flowable, times(1)).getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, "def-B");
    }

    @Test
    void spendsTheScanCapOnCmmnOnlyViaScopeTypeFilter() {
        health(scopeTypeCapable());
        when(flowable.listCmmnDeadLetterJobs(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(), 0, 0, 50));

        service.outOfScopeDeadLetters(ENGINE);

        // The cmmn-api DLQ honors ?scopeType=cmmn (unlike the process-api DLQ), so the scan
        // cap is spent on CMMN rows only — BPMN projections never crowd CMMN past the cap.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> filters = ArgumentCaptor.forClass(Map.class);
        verify(flowable).listCmmnDeadLetterJobs(any(), any(), filters.capture(), anyInt(), anyInt());
        assertThat(filters.getValue()).containsEntry("scopeType", "cmmn");
    }

    @Test
    void anUndeployedDefinitionDegradesToNullNameNeverFailsTheSlice() {
        health(scopeTypeCapable());
        when(flowable.listCmmnDeadLetterJobs(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(cmmnRow("j1", "case-1", "gone")), 1, 0, 50));
        when(flowable.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, "gone"))
                .thenReturn(null); // 404 → null

        OutOfScopeDeadLetters result = service.outOfScopeDeadLetters(ENGINE);

        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.caseDefinitionId()).isEqualTo("gone");
            assertThat(job.caseDefinitionKey()).isNull();
            assertThat(job.caseDefinitionName()).isNull();
        });
    }

    @Test
    void cmmnScopeReportsLaneCountsAndDistinctFailedCases() {
        health(scopeTypeCapable());
        // Two dead-letter jobs on ONE case + a third on another → FAILED = 2 DISTINCT cases.
        when(flowable.listCmmnDeadLetterJobs(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(
                                cmmnRow("j1", "case-1", "def-A"),
                                cmmnRow("j2", "case-1", "def-A"),
                                cmmnRow("j3", "case-2", "def-B")),
                        3,
                        0,
                        50));
        when(flowable.getCmmnCaseDefinition(any(), any(), any())).thenReturn(def("k", "n"));
        when(flowable.countHistoricCmmnCaseInstances(any(), any(), eq("active"), any()))
                .thenReturn(5L);
        when(flowable.countHistoricCmmnCaseInstances(any(), any(), eq("completed"), any()))
                .thenReturn(12L);
        when(flowable.countHistoricCmmnCaseInstances(any(), any(), eq("terminated"), any()))
                .thenReturn(1L);

        CmmnScopeFacet facet = service.cmmnScope(ENGINE);

        assertThat(facet.lanes().active()).isEqualTo(5);
        assertThat(facet.lanes().completed()).isEqualTo(12);
        assertThat(facet.lanes().terminated()).isEqualTo(1);
        assertThat(facet.lanes().failed()).isEqualTo(2); // distinct cases, not 3 jobs
        assertThat(facet.deadletters().jobs()).extracting(CmmnDeadLetterJob::id).containsExactly("j1", "j2", "j3");
    }

    @Test
    void aLaneQueryFailureDegradesToNullNeverZero() {
        health(scopeTypeCapable());
        when(flowable.listCmmnDeadLetterJobs(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(), 0, 0, 50));
        when(flowable.countHistoricCmmnCaseInstances(any(), any(), eq("active"), any()))
                .thenReturn(3L);
        when(flowable.countHistoricCmmnCaseInstances(any(), any(), eq("completed"), any()))
                .thenThrow(new RuntimeException("engine hiccup"));
        when(flowable.countHistoricCmmnCaseInstances(any(), any(), eq("terminated"), any()))
                .thenReturn(0L);

        CmmnScopeFacet facet = service.cmmnScope(ENGINE);

        assertThat(facet.lanes().active()).isEqualTo(3);
        assertThat(facet.lanes().completed()).isNull(); // unknown, NOT a confident 0
        assertThat(facet.lanes().terminated()).isZero();
        assertThat(facet.lanes().failed()).isZero();
    }

    @Test
    void cmmnScopeGatesPreSixEightBeforeAnyLaneQuery() {
        health(new EngineCapabilities(true, true, false, false, true)); // scopeType absent

        assertThatThrownBy(() -> service.cmmnScope(ENGINE))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unavailable"));
        verify(flowable, never()).countHistoricCmmnCaseInstances(any(), any(), any(), any());
    }

    private static EngineCapabilities scopeTypeCapable() {
        return new EngineCapabilities(true, true, true, true, true);
    }

    private static Map<String, Object> cmmnRow(String id, String caseInstanceId, String caseDefinitionId) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("caseInstanceId", caseInstanceId);
        m.put("caseDefinitionId", caseDefinitionId);
        m.put("elementName", "Failing service");
        m.put("retries", 0);
        return m;
    }

    private static Map<String, Object> def(String key, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
}

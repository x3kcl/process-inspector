package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.PersonTaskSearchResponse;
import io.inspector.dto.PersonTaskSearchResponse.PersonTaskRow;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rung 2 (mock the engine client) — person-centric task search (#99). Load-bearing properties:
 * the assignee/candidate legs dedupe by task id (an assigned task never double-counts as a
 * candidate hit), an unreachable engine degrades to a labeled failure without failing the whole
 * search, and S2 read-scoping narrows the fan-out exactly like {@link SearchServiceScopeTest}.
 */
class PersonTaskSearchServiceTest {

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final InspectorProperties props = new InspectorProperties(4, 10, null, null, null, List.of());
    private final PersonTaskSearchService service = new PersonTaskSearchService(registry, flowable, props);

    private final EngineConfig engineA = TestEngines.engine("engine-a", "http://a");
    private final EngineConfig engineB = TestEngines.engineInTenant("engine-b", "http://b", "tenant-b");

    private static Map<String, Object> taskJson(String id, String processDefinitionId, String assignee) {
        // assignee may legitimately be null (an unassigned candidate-only row) — Map.of() rejects
        // null values, so build with a mutable map instead.
        Map<String, Object> json = new java.util.HashMap<>();
        json.put("id", id);
        json.put("name", "Approve " + id);
        json.put("processInstanceId", "pi-" + id);
        json.put("processDefinitionId", processDefinitionId);
        json.put("taskDefinitionKey", "approveTask");
        json.put("assignee", assignee);
        json.put("createTime", "2026-07-10T10:00:00.000+0000");
        json.put("dueDate", "2026-07-15T10:00:00.000+0000");
        return json;
    }

    @Test
    void assignedTaskIsNeverDoubleCountedAsCandidate() {
        when(registry.all()).thenReturn(List.of(engineA));
        // The SAME task ("t1") comes back on both legs — Flowable does this when an assignee also
        // sits in the candidate list; it must appear exactly once, tagged ASSIGNED (outranks candidate).
        when(flowable.listTasksByAssignee(eq(engineA), eq(CallPriority.INTERACTIVE), eq("bob"), anyInt()))
                .thenReturn(new FlowablePage(List.of(taskJson("t1", "approval:3:x", "bob")), 1, 0, 1));
        when(flowable.listTasksByCandidateUser(eq(engineA), eq(CallPriority.INTERACTIVE), eq("bob"), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(taskJson("t1", "approval:3:x", "bob"), taskJson("t2", "approval:3:x", null)), 2, 0, 2));

        PersonTaskSearchResponse res = service.search("bob", null, null);

        assertThat(res.rows()).hasSize(2);
        PersonTaskRow t1 = res.rows().stream()
                .filter(r -> "t1".equals(r.taskId()))
                .findFirst()
                .orElseThrow();
        assertThat(t1.matchReason()).isEqualTo(PersonTaskRow.MATCH_ASSIGNED);
        PersonTaskRow t2 = res.rows().stream()
                .filter(r -> "t2".equals(r.taskId()))
                .findFirst()
                .orElseThrow();
        assertThat(t2.matchReason()).isEqualTo(PersonTaskRow.MATCH_CANDIDATE);
        assertThat(t2.processDefinitionKey()).isEqualTo("approval");
        assertThat(res.perEngine().get("engine-a").ok()).isTrue();
    }

    @Test
    void anUnreachableEngineDegradesWithoutFailingTheWholeSearch() {
        when(registry.all()).thenReturn(List.of(engineA, engineB));
        when(flowable.listTasksByAssignee(eq(engineA), any(), eq("bob"), anyInt()))
                .thenReturn(new FlowablePage(List.of(taskJson("t1", "approval:3:x", "bob")), 1, 0, 1));
        when(flowable.listTasksByCandidateUser(eq(engineA), any(), eq("bob"), anyInt()))
                .thenReturn(FlowablePage.empty());
        when(flowable.listTasksByAssignee(eq(engineB), any(), eq("bob"), anyInt()))
                .thenThrow(new RuntimeException("engine-b unreachable"));
        when(flowable.listTasksByCandidateUser(eq(engineB), any(), eq("bob"), anyInt()))
                .thenReturn(FlowablePage.empty());

        PersonTaskSearchResponse res = service.search("bob", null, null);

        assertThat(res.rows()).hasSize(1);
        assertThat(res.perEngine().get("engine-a").ok()).isTrue();
        assertThat(res.perEngine().get("engine-b").ok()).isFalse();
        assertThat(res.perEngine().get("engine-b").error()).contains("engine-b unreachable");
    }

    @Test
    void anOutOfScopeEngineIsLabeledAndNeverQueried() {
        when(registry.all()).thenReturn(List.of(engineA, engineB));
        when(registry.resolve("engine-b")).thenReturn(Optional.of(engineB));

        PersonTaskSearchResponse res = service.search("bob", Set.of("engine-b"), Set.of());

        verifyNoInteractions(flowable);
        assertThat(res.perEngine().get("engine-b").ok()).isFalse();
        assertThat(res.perEngine().get("engine-b").error()).contains("outside your access scope");
        assertThat(res.rows()).isEmpty();
    }

    @Test
    void resultsSortBySoonestDueDateFirst() {
        when(registry.all()).thenReturn(List.of(engineA));
        Map<String, Object> soon = taskJson("t-soon", "approval:1:x", "bob");
        soon.put("dueDate", "2026-07-11T10:00:00.000+0000");
        Map<String, Object> later = taskJson("t-later", "approval:1:x", "bob");
        later.put("dueDate", "2026-07-20T10:00:00.000+0000");
        when(flowable.listTasksByAssignee(eq(engineA), any(), eq("bob"), anyInt()))
                .thenReturn(new FlowablePage(List.of(later, soon), 2, 0, 2));
        when(flowable.listTasksByCandidateUser(eq(engineA), any(), eq("bob"), anyInt()))
                .thenReturn(FlowablePage.empty());

        PersonTaskSearchResponse res = service.search("bob", null, null);

        assertThat(res.rows()).extracting(PersonTaskRow::taskId).containsExactly("t-soon", "t-later");
    }
}

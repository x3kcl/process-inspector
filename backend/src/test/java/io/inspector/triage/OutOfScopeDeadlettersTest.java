package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the CMMN out-of-scope dead-letter count is a pure fold over the DEADLETTER-lane
 * rows, gated on the engine's ability to discriminate scope (the {@code scopeType}
 * capability, ~6.8+). Proven live (cmmn-wire-shape-spike): the process-api DLQ projection
 * lists a CMMN job as a {@code processInstanceId:null} orphan and serializes NO
 * {@code scopeType} at all — so a null process-instance id IS the discriminator, and on an
 * engine too old to be trusted the count must be unknown (null), never a misleading zero.
 */
class OutOfScopeDeadlettersTest {

    /** A normal BPMN dead-letter job — always carries its process-instance id. */
    private static Map<String, Object> bpmnJob(String pid) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "j-" + pid);
        job.put("processInstanceId", pid);
        return job;
    }

    /** A CMMN dead-letter job as the process-api DLQ projects it: null pid, no scopeType key. */
    private static Map<String, Object> cmmnOrphanJob(String id) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", id);
        job.put("processInstanceId", null);
        return job;
    }

    /** A job an engine DOES tag with an explicit non-bpmn scopeType. */
    private static Map<String, Object> scopedJob(String pid, String scopeType) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "s-" + pid);
        job.put("processInstanceId", pid);
        job.put("scopeType", scopeType);
        return job;
    }

    @Test
    void countsNullProcessInstanceRowsAsOutOfScopeWhenScopeTypeCapable() {
        List<Map<String, Object>> dlq = List.of(bpmnJob("p1"), bpmnJob("p2"), cmmnOrphanJob("c1"), cmmnOrphanJob("c2"));
        assertThat(TriageAggregationService.outOfScopeDeadletters(dlq, true)).isEqualTo(2);
    }

    @Test
    void returnsNullWhenTheEngineCannotDiscriminateScope() {
        List<Map<String, Object>> dlq = List.of(bpmnJob("p1"), cmmnOrphanJob("c1"));
        assertThat(TriageAggregationService.outOfScopeDeadletters(dlq, false)).isNull();
    }

    @Test
    void anExplicitNonBpmnScopeTypeIsOutOfScopeEvenWithAProcessInstanceId() {
        List<Map<String, Object>> dlq = List.of(bpmnJob("p1"), scopedJob("x1", "cmmn"));
        assertThat(TriageAggregationService.outOfScopeDeadletters(dlq, true)).isEqualTo(1);
    }

    @Test
    void aCleanBpmnOnlyLaneIsZeroNotNull() {
        assertThat(TriageAggregationService.outOfScopeDeadletters(List.of(bpmnJob("p1")), true))
                .isEqualTo(0);
        assertThat(TriageAggregationService.outOfScopeDeadletters(List.of(), true))
                .isEqualTo(0);
    }
}

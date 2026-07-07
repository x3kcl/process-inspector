package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.triage.TriageAggregationService.OutOfScope;
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

    /** A complete (uncapped) DEADLETTER scan of the given rows on a scope-capable engine. */
    private static OutOfScope complete(List<Map<String, Object>> dlq, boolean scopeTypeCapable) {
        return TriageAggregationService.outOfScopeDeadletters(dlq, false, scopeTypeCapable);
    }

    @Test
    void countsNullProcessInstanceRowsAsOutOfScopeWhenScopeTypeCapable() {
        List<Map<String, Object>> dlq = List.of(bpmnJob("p1"), bpmnJob("p2"), cmmnOrphanJob("c1"), cmmnOrphanJob("c2"));
        OutOfScope result = complete(dlq, true);
        assertThat(result.count()).isEqualTo(2);
        // A complete scan is exact — the count is NOT a lower bound.
        assertThat(result.deadletterTruncated()).isFalse();
    }

    @Test
    void returnsNullCountWhenTheEngineCannotDiscriminateScope() {
        List<Map<String, Object>> dlq = List.of(bpmnJob("p1"), cmmnOrphanJob("c1"));
        assertThat(complete(dlq, false).count()).isNull();
    }

    @Test
    void anExplicitNonBpmnScopeTypeIsOutOfScopeEvenWithAProcessInstanceId() {
        List<Map<String, Object>> dlq = List.of(bpmnJob("p1"), scopedJob("x1", "cmmn"));
        assertThat(complete(dlq, true).count()).isEqualTo(1);
    }

    @Test
    void aCleanBpmnOnlyLaneIsZeroNotNull() {
        assertThat(complete(List.of(bpmnJob("p1")), true).count()).isEqualTo(0);
        assertThat(complete(List.of(), true).count()).isEqualTo(0);
    }

    /**
     * The floor flag: when the DEADLETTER lane's own scan hit the cap, the concrete count is
     * a lower bound (orphans may lie past the cap) — the UI must render it as ≥N. This is the
     * H1 fix: the DEADLETTER lane's specific truncation, captured before it is swallowed into
     * the unified {@code dlqScan} marker.
     */
    @Test
    void aTruncatedDeadletterScanFloorsTheCount() {
        List<Map<String, Object>> dlq = List.of(bpmnJob("p1"), cmmnOrphanJob("c1"), cmmnOrphanJob("c2"));
        OutOfScope result = TriageAggregationService.outOfScopeDeadletters(dlq, true, true);
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.deadletterTruncated()).isTrue();
    }

    /** A truncated scan on an engine that cannot discriminate scope stays unknown — no floor. */
    @Test
    void aTruncatedScanOnAnIncapableEngineIsStillNullAndUnfloored() {
        OutOfScope result = TriageAggregationService.outOfScopeDeadletters(List.of(cmmnOrphanJob("c1")), true, false);
        assertThat(result.count()).isNull();
        assertThat(result.deadletterTruncated()).isFalse();
    }
}

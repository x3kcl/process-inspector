package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.dto.InstanceJobs.JobDto;
import io.inspector.dto.InstanceTimeline.TimelineActivity;
import io.inspector.dto.InstanceVariables.VariableDto;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the pure row-mapping core of the Stage 2 detail endpoints —
 * the typed-ledger contract (R-UXQ-13: engine type verbatim, TYPED value preserved, byte
 * caps honest) and the resource-name derivation for the diagram proxy. Engine wire shapes
 * are proven at rung 4 (DetailResolveIT); these pin the mapping semantics.
 */
class InstanceDetailMappingTest {

    /* ---------- the typed variable ledger ---------- */

    @Test
    void scalarValuesStayTyped_neverStringified() {
        VariableDto integer = InstanceDetailService.typedRow(
                Map.of("name", "amount", "type", "integer", "value", 100), "global", null, null);
        assertThat(integer.type()).isEqualTo("integer");
        assertThat(integer.value()).isEqualTo(100); // a JSON number, not "100"
        assertThat(integer.truncated()).isFalse();

        VariableDto bool = InstanceDetailService.typedRow(
                Map.of("name", "approved", "type", "boolean", "value", false), "global", null, null);
        assertThat(bool.value()).isEqualTo(false);
    }

    @Test
    void nullValuesAreExplicitRowsNotDroppedOnes() {
        Map<String, Object> row = new HashMap<>();
        row.put("name", "optionalNote");
        row.put("type", "null");
        row.put("value", null);
        VariableDto dto = InstanceDetailService.typedRow(row, "global", null, null);
        assertThat(dto.name()).isEqualTo("optionalNote");
        assertThat(dto.value()).isNull();
        assertThat(dto.truncated()).isFalse(); // absent by nature, not by cap
    }

    @Test
    void structuredValuesOverTheCapShipTruncatedWithTheirSize() {
        String blob = "x".repeat(InstanceDetailService.STRUCTURED_PREVIEW_CAP_BYTES + 1);
        VariableDto big = InstanceDetailService.typedRow(
                Map.of("name", "payload", "type", "string", "value", blob), "global", null, null);
        assertThat(big.truncated()).isTrue();
        assertThat(big.value()).isNull();
        assertThat(big.sizeBytes()).isGreaterThan((long) InstanceDetailService.STRUCTURED_PREVIEW_CAP_BYTES);

        Map<String, Object> json = Map.of("inner", "y".repeat(InstanceDetailService.STRUCTURED_PREVIEW_CAP_BYTES));
        VariableDto bigJson = InstanceDetailService.typedRow(
                Map.of("name", "doc", "type", "json", "value", json), "global", null, null);
        assertThat(bigJson.truncated()).isTrue();
        assertThat(bigJson.value()).isNull();
    }

    @Test
    void structuredValuesUnderTheCapPassThroughWithMeasuredSize() {
        Map<String, Object> json = Map.of("enabled", true, "attempts", 3);
        VariableDto dto = InstanceDetailService.typedRow(
                Map.of("name", "retryPolicy", "type", "json", "value", json), "local", "exec-9", null);
        assertThat(dto.value()).isEqualTo(json);
        assertThat(dto.truncated()).isFalse();
        assertThat(dto.sizeBytes()).isNotNull().isPositive();
        assertThat(dto.scope()).isEqualTo("local");
        assertThat(dto.executionId()).isEqualTo("exec-9");
    }

    /* ---------- diagram resource-name derivation ---------- */

    @Test
    void resourceNameFieldWinsWhenTheEngineSerializesIt() {
        assertThat(InstanceDetailService.resourceNameOf(
                        Map.of("resourceName", "demoParent.bpmn20.xml", "resource", "http://x/ignored")))
                .isEqualTo("demoParent.bpmn20.xml");
    }

    @Test
    void resourceUrlTailIsDecodedIntoTheDeploymentResourceName() {
        assertThat(
                        InstanceDetailService.resourceNameOf(
                                Map.of(
                                        "resource",
                                        "http://host/flowable-rest/service/repository/deployments/42/resources/processes%2Fdemo%20order.bpmn20.xml")))
                .isEqualTo("processes/demo order.bpmn20.xml");
        assertThat(InstanceDetailService.resourceNameOf(Map.of("resource", "http://host/no-resources-segment")))
                .isNull();
        assertThat(InstanceDetailService.resourceNameOf(Map.of())).isNull();
    }

    /* ---------- job + timeline rows ---------- */

    @Test
    void jobRowsCarryTheLaneAndTypedRetries() {
        JobDto dto = InstanceDetailService.jobRow(
                Map.of(
                        "id", "j-1",
                        "retries", 0,
                        "createTime", "2026-07-06T09:00:00Z",
                        "exceptionMessage", "/ by zero",
                        "elementId", "chargePayment"),
                JobLaneKind.DEADLETTER);
        assertThat(dto.lane()).isEqualTo("DEADLETTER");
        assertThat(dto.retries()).isZero();
        assertThat(dto.elementId()).isEqualTo("chargePayment");
    }

    @Test
    void timelineRowsKeepTheCallActivityChildLink() {
        TimelineActivity dto = InstanceDetailService.timelineRow(Map.of(
                "id", "act-1",
                "activityId", "callPayment",
                "activityType", "callActivity",
                "durationInMillis", 1234,
                "calledProcessInstanceId", "child-7"));
        assertThat(dto.durationMs()).isEqualTo(1234L);
        assertThat(dto.calledProcessInstanceId()).isEqualTo("child-7");
        assertThat(dto.endTime()).isNull();
    }
}

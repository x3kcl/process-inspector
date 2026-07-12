package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.client.ExternalJobApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceTimeline;
import io.inspector.dto.InstanceTimeline.LiveJobState;
import io.inspector.dto.InstanceTimeline.TimelineActivity;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rung 1/2 (unit-test-patterns): the timeline sub-lane recursion and the live-job join over a
 * MOCKED engine client — the cycle guard (R-TEST-07: a real engine cannot produce a
 * {@code calledProcessInstanceId} cycle, so the guard is unsatisfiable at rung 4), the
 * depth/breadth caps, the phantom-node union for a rolled-back async dead-letter, and the
 * FAILED-over-RETRYING precedence. The authoritative join-against-real-wire proof is the
 * rung-4 {@link InstanceTimelineIT} (iron rule: never mock Flowable for the FAILED join).
 */
class InstanceTimelineServiceTest {

    private static final String ENGINE = "e";

    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test");
    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final ExternalJobApiClient externalJobs = mock(ExternalJobApiClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private InstanceDetailService service;

    @BeforeEach
    void setUp() {
        when(registry.require(ENGINE)).thenReturn(engine);
        service = serviceWithMaxDepth(null); // null → default depth 10
        // Healthy by default: no jobs on any lane. Individual tests override specific lanes.
        when(flowable.listJobs(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(FlowablePage.empty());
    }

    private InstanceDetailService serviceWithMaxDepth(Integer maxDepth) {
        return new InstanceDetailService(
                registry,
                flowable,
                externalJobs,
                new InspectorProperties(null, maxDepth, null, null, List.of()),
                mock(io.inspector.audit.ProtectedInstanceRepository.class));
    }

    /* ================= cycle guard (R-TEST-07) ================= */

    @Test
    void cycleGuard_stopsAtTheRevisitedInstance_noInfiniteRecursion() {
        historicExists("A");
        activities("A", callActivityRow("a1", "callB", "B"));
        activities("B", callActivityRow("b1", "callA", "A")); // B calls back to A — a 2-cycle

        InstanceTimeline timeline = service.timeline(ENGINE, "A");

        assertThat(timeline.activities()).hasSize(1);
        TimelineActivity a1 = timeline.activities().get(0);
        assertThat(a1.calledProcessInstanceId()).isEqualTo("B");
        assertThat(a1.children()).hasSize(1); // B expanded exactly once
        TimelineActivity b1 = a1.children().get(0);
        assertThat(b1.calledProcessInstanceId()).isEqualTo("A"); // the row still points back...
        assertThat(b1.children()).isEmpty(); // ...but the guard refused to re-expand A
        assertThat(b1.isCapped()).isFalse(); // a guard stop is not a cap — the drill-out link stands
    }

    /* ================= depth cap ================= */

    @Test
    void depthCap_stopsExpansionAtTheLimit_andFlagsIsCapped() {
        service = serviceWithMaxDepth(1);
        historicExists("A");
        activities("A", callActivityRow("a1", "callB", "B")); // A(depth 0) → B(depth 1)
        activities("B", callActivityRow("b1", "callC", "C")); // B(depth 1 == max) must NOT expand

        InstanceTimeline timeline = service.timeline(ENGINE, "A");

        TimelineActivity b1 = timeline.activities().get(0).children().get(0);
        assertThat(b1.children()).isEmpty();
        assertThat(b1.isCapped()).isTrue();
    }

    /* ================= phantom-node union (async rollback) ================= */

    @Test
    void phantomNode_synthesizesTheFailingAsyncActivity_absentFromHistory() {
        historicExists("A");
        // History shows only a finished prior activity; the failing async task rolled its row back.
        activities("A", finishedRow("h1", "validate"));
        // A dead-letter job is parked on activity "charge" — which has NO historic row.
        deadLetterJobs("A", Map.of("id", "job-1", "elementId", "charge"));

        InstanceTimeline timeline = service.timeline(ENGINE, "A");

        TimelineActivity phantom = timeline.activities().stream()
                .filter(a -> "charge".equals(a.activityId()))
                .findFirst()
                .orElseThrow();
        assertThat(phantom.liveJobState()).isEqualTo(LiveJobState.FAILED);
        assertThat(phantom.endTime()).isNull(); // unfinished by construction
        assertThat(phantom.id()).isNull(); // synthesized — not a real ACT_HI_ACTINST row
    }

    /* ================= live annotation + precedence ================= */

    @Test
    void liveAnnotation_deadLetterBeatsRetrying_onTheUnfinishedRow_noDuplicate() {
        historicExists("A");
        activities("A", unfinishedRow("h1", "charge")); // the failing task DOES have an open row here
        executableJobs("A", Map.of("id", "j-exec", "elementId", "charge", "exceptionMessage", "boom"));
        deadLetterJobs("A", Map.of("id", "j-dl", "elementId", "charge"));

        InstanceTimeline timeline = service.timeline(ENGINE, "A");

        assertThat(timeline.activities()).hasSize(1); // annotated in place — no phantom duplicate
        TimelineActivity charge = timeline.activities().get(0);
        assertThat(charge.activityId()).isEqualTo("charge");
        assertThat(charge.liveJobState()).isEqualTo(LiveJobState.FAILED);
    }

    /* ================= fixtures ================= */

    private void historicExists(String id) {
        when(flowable.getHistoricProcessInstance(engine, CallPriority.INTERACTIVE, id))
                .thenReturn(Map.of("id", id));
    }

    @SafeVarargs
    private void activities(String instanceId, Map<String, Object>... rows) {
        when(flowable.listHistoricActivities(
                        eq(engine), eq(CallPriority.INTERACTIVE), eq(instanceId), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(rows), rows.length, 0, 50));
    }

    private void deadLetterJobs(String instanceId, Map<String, Object> job) {
        when(flowable.listJobs(
                        eq(engine),
                        eq(CallPriority.INTERACTIVE),
                        eq(JobLaneKind.DEADLETTER),
                        any(),
                        anyInt(),
                        anyInt()))
                .thenReturn(new FlowablePage(List.of(job), 1, 0, 50));
    }

    private void executableJobs(String instanceId, Map<String, Object> job) {
        when(flowable.listJobs(
                        eq(engine),
                        eq(CallPriority.INTERACTIVE),
                        eq(JobLaneKind.EXECUTABLE),
                        any(),
                        anyInt(),
                        anyInt()))
                .thenReturn(new FlowablePage(List.of(job), 1, 0, 50));
    }

    private static Map<String, Object> callActivityRow(String id, String activityId, String childInstanceId) {
        return Map.of(
                "id", id,
                "activityId", activityId,
                "activityType", "callActivity",
                "startTime", "2026-07-07T00:00:00.000Z",
                "calledProcessInstanceId", childInstanceId);
    }

    private static Map<String, Object> finishedRow(String id, String activityId) {
        return Map.of(
                "id",
                id,
                "activityId",
                activityId,
                "activityType",
                "serviceTask",
                "startTime",
                "2026-07-07T00:00:00.000Z",
                "endTime",
                "2026-07-07T00:00:01.000Z",
                "durationInMillis",
                1000);
    }

    private static Map<String, Object> unfinishedRow(String id, String activityId) {
        return Map.of(
                "id",
                id,
                "activityId",
                activityId,
                "activityType",
                "serviceTask",
                "startTime",
                "2026-07-07T00:00:00.000Z");
    }
}

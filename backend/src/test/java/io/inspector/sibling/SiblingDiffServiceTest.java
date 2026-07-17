package io.inspector.sibling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.client.FlowablePage;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
import io.inspector.dto.InstanceVariables.VariableDto;
import io.inspector.dto.NearestSiblingResponse;
import io.inspector.dto.SiblingDiffResponse;
import io.inspector.dto.SiblingDiffResponse.PathActivity;
import io.inspector.dto.SiblingDiffResponse.TimingDelta;
import io.inspector.dto.SiblingDiffResponse.VariableChange;
import io.inspector.dto.SiblingDiffResponse.VariableDelta;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the pure comparison core of the sibling diff (variable /
 * path / timing joins), plus rung-2 orchestration over a mocked engine client — the
 * nearest-sibling selection and the historic-only wiring. The real engine wire shapes are a
 * rung-4 concern; these pin the join semantics and the "historic queries only" contract.
 */
class SiblingDiffServiceTest {

    private static VariableDto var(String name, String type, Object value) {
        return new VariableDto(name, type, value, false, null, "global", null, null);
    }

    private static VariableDto truncated(String name, long sizeBytes) {
        return new VariableDto(name, "string", null, true, sizeBytes, "global", null, null);
    }

    private static PathActivity act(String id, Long durationMs) {
        return new PathActivity(
                id,
                id,
                "userTask",
                "2026-07-07T00:00:00Z",
                durationMs == null ? null : "later",
                durationMs,
                durationMs == null);
    }

    /* ================= variable diff ================= */

    @Test
    void variableDiff_classifiesEveryKeyRelativeToSubject() {
        List<VariableDelta> deltas = SiblingDiffService.diffVariables(
                List.of(var("amount", "integer", 100), var("region", "string", "EU"), var("onlyMine", "string", "x")),
                List.of(
                        var("amount", "integer", 250),
                        var("region", "string", "EU"),
                        var("onlyTheirs", "string", "y")));

        Map<String, VariableChange> byName =
                deltas.stream().collect(java.util.stream.Collectors.toMap(VariableDelta::name, VariableDelta::change));
        assertThat(byName)
                .containsEntry("amount", VariableChange.CHANGED) // 100 vs 250
                .containsEntry("region", VariableChange.SAME)
                .containsEntry("onlyMine", VariableChange.ONLY_IN_SUBJECT)
                .containsEntry("onlyTheirs", VariableChange.ONLY_IN_SIBLING);
        // sorted by name — stable rendering
        assertThat(deltas.stream().map(VariableDelta::name))
                .containsExactly("amount", "onlyMine", "onlyTheirs", "region");
    }

    @Test
    void variableDiff_neverAssertsEqualityWhenEitherSideExceedsThePreviewCap() {
        // The full blob is deliberately NOT fetched to compare (SPEC §5.2) — flag it instead.
        List<VariableDelta> deltas = SiblingDiffService.diffVariables(
                List.of(truncated("payload", 512 * 1024)), List.of(var("payload", "string", "small")));
        assertThat(deltas)
                .singleElement()
                .extracting(VariableDelta::change)
                .isEqualTo(VariableChange.DIFFER_BEYOND_PREVIEW);
    }

    @Test
    void variableDiff_sameTypeAndValueIsSame_typeChangeIsChanged() {
        assertThat(SiblingDiffService.diffVariables(List.of(var("f", "integer", 1)), List.of(var("f", "integer", 1)))
                        .get(0)
                        .change())
                .isEqualTo(VariableChange.SAME);
        assertThat(SiblingDiffService.diffVariables(List.of(var("f", "string", "1")), List.of(var("f", "integer", 1)))
                        .get(0)
                        .change())
                .isEqualTo(VariableChange.CHANGED);
    }

    /* ================= path divergence ================= */

    @Test
    void pathDivergence_splitsCommonFromEachSidesUniqueActivities() {
        var path = SiblingDiffService.divergePath(
                List.of(act("start", 1L), act("validate", 2L), act("reject", 3L)),
                List.of(act("start", 1L), act("validate", 2L), act("approve", 4L)));
        assertThat(path.common()).containsExactly("start", "validate");
        assertThat(path.onlyInSubject()).containsExactly("reject");
        assertThat(path.onlyInSibling()).containsExactly("approve");
    }

    @Test
    void pathDivergence_dedupesLoopedActivitiesIntoTheSetOnce() {
        var path = SiblingDiffService.divergePath(
                List.of(act("loop", 1L), act("loop", 1L), act("loop", 1L)), List.of(act("loop", 1L)));
        assertThat(path.common()).containsExactly("loop");
        assertThat(path.onlyInSubject()).isEmpty();
    }

    /* ================= timing deltas ================= */

    @Test
    void timing_sumsLoopOccurrencesAndSignsTheDelta() {
        List<TimingDelta> timings = SiblingDiffService.timingDeltas(
                List.of(act("step", 300L), act("step", 200L)), List.of(act("step", 100L)));
        TimingDelta step = timings.get(0);
        assertThat(step.subjectMs()).isEqualTo(500L); // summed across two occurrences
        assertThat(step.siblingMs()).isEqualTo(100L);
        assertThat(step.deltaMs()).isEqualTo(400L); // subject slower by 400ms
        assertThat(step.subjectOccurrences()).isEqualTo(2);
    }

    @Test
    void timing_stalledStepHasNoSubjectDurationButIsFlaggedUnfinished() {
        // The subject dead-lettered ON "charge": an open activity, null duration — the signal.
        List<TimingDelta> timings =
                SiblingDiffService.timingDeltas(List.of(act("charge", null)), List.of(act("charge", 80L)));
        TimingDelta charge = timings.get(0);
        assertThat(charge.subjectMs()).isNull();
        assertThat(charge.subjectUnfinished()).isTrue();
        assertThat(charge.siblingMs()).isEqualTo(80L);
        assertThat(charge.deltaMs()).isNull(); // uncomputable — subject never completed the step
    }

    /* ================= nearest-sibling orchestration (mocked engine) ================= */

    @Test
    void nearestSibling_picksMostRecentCompleted_skippingTheSubjectItself() {
        EngineRegistry registry = mock(EngineRegistry.class);
        ProcessApiClient flowable = mock(ProcessApiClient.class);
        InstanceDetailService detail = mock(InstanceDetailService.class);
        EngineConfig engine = TestEngines.engine("engine-a", "http://engine-a");
        when(registry.resolveOrNotFound("engine-a")).thenReturn(engine);
        when(detail.requireHistoric(eq(engine), eq("subject-1")))
                .thenReturn(Map.of("id", "subject-1", "processDefinitionId", "payment:3:abc"));

        // endTime-desc page: the subject appears (defensive) then the real winner.
        when(flowable.queryHistoricProcessInstances(eq(engine), any(), any()))
                .thenReturn(new FlowablePage(
                        List.of(
                                Map.of("id", "subject-1", "endTime", "2026-07-07T09:00:00Z"),
                                Map.of("id", "good-42", "businessKey", "ORD-42", "endTime", "2026-07-07T08:00:00Z")),
                        2,
                        0,
                        25));

        NearestSiblingResponse res =
                new SiblingDiffService(registry, flowable, detail).nearestSibling("engine-a", "subject-1");
        assertThat(res.found()).isTrue();
        assertThat(res.sibling().processInstanceId()).isEqualTo("good-42");
        assertThat(res.processDefinitionKey()).isEqualTo("payment");
        assertThat(res.definitionVersion()).isEqualTo(3);
    }

    @Test
    void nearestSibling_noCompletedRun_returnsFoundFalseNotAnError() {
        EngineRegistry registry = mock(EngineRegistry.class);
        ProcessApiClient flowable = mock(ProcessApiClient.class);
        InstanceDetailService detail = mock(InstanceDetailService.class);
        EngineConfig engine = TestEngines.engine("engine-a", "http://engine-a");
        when(registry.resolveOrNotFound("engine-a")).thenReturn(engine);
        when(detail.requireHistoric(eq(engine), eq("subject-1")))
                .thenReturn(Map.of("id", "subject-1", "processDefinitionId", "payment:3:abc"));
        when(flowable.queryHistoricProcessInstances(eq(engine), any(), any())).thenReturn(FlowablePage.empty());

        NearestSiblingResponse res =
                new SiblingDiffService(registry, flowable, detail).nearestSibling("engine-a", "subject-1");
        assertThat(res.found()).isFalse();
        assertThat(res.sibling()).isNull();
        assertThat(res.processDefinitionKey()).isEqualTo("payment");
    }

    @Test
    void diff_isHistoricOnly_andComposesTheThreeWayResult() {
        EngineRegistry registry = mock(EngineRegistry.class);
        ProcessApiClient flowable = mock(ProcessApiClient.class);
        InstanceDetailService detail = mock(InstanceDetailService.class);
        EngineConfig engine = TestEngines.engine("engine-a", "http://engine-a");
        when(registry.resolveOrNotFound("engine-a")).thenReturn(engine);
        when(detail.requireHistoric(eq(engine), eq("subject-1")))
                .thenReturn(Map.of("id", "subject-1", "processDefinitionId", "payment:3:abc", "businessKey", "ORD-1"));
        when(detail.requireHistoric(eq(engine), eq("good-42")))
                .thenReturn(Map.of(
                        "id", "good-42", "processDefinitionId", "payment:3:abc", "endTime", "2026-07-07T08:00:00Z"));

        when(flowable.listHistoricVariableInstances(eq(engine), any(), eq("subject-1"), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(Map.of("name", "amount", "type", "integer", "value", 100)), 1, 0, 200));
        when(flowable.listHistoricVariableInstances(eq(engine), any(), eq("good-42"), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(Map.of("name", "amount", "type", "integer", "value", 250)), 1, 0, 200));
        when(flowable.listHistoricActivities(eq(engine), any(), eq("subject-1"), anyInt(), anyInt()))
                .thenReturn(
                        new FlowablePage(List.of(activityRow("start", 5L), activityRow("charge", null)), 2, 0, 200));
        when(flowable.listHistoricActivities(eq(engine), any(), eq("good-42"), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(List.of(activityRow("start", 5L), activityRow("done", 3L)), 2, 0, 200));

        SiblingDiffResponse res =
                new SiblingDiffService(registry, flowable, detail).diff("engine-a", "subject-1", "good-42");

        assertThat(res.sameDefinition()).isTrue();
        assertThat(res.subject().processInstanceId()).isEqualTo("subject-1");
        assertThat(res.variables())
                .singleElement()
                .extracting(VariableDelta::change)
                .isEqualTo(VariableChange.CHANGED);
        assertThat(res.path().onlyInSubject()).containsExactly("charge");
        assertThat(res.path().onlyInSibling()).containsExactly("done");
        assertThat(res.path().common()).containsExactly("start");
    }

    private static Map<String, Object> activityRow(String activityId, Long durationMs) {
        java.util.HashMap<String, Object> row = new java.util.HashMap<>();
        row.put("activityId", activityId);
        row.put("activityName", activityId);
        row.put("activityType", "userTask");
        row.put("startTime", "2026-07-07T00:00:00Z");
        if (durationMs != null) {
            row.put("endTime", "2026-07-07T00:00:01Z");
            row.put("durationInMillis", durationMs);
        }
        return row;
    }
}

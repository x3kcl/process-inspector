package io.inspector.detail;

import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService.Lanes;
import io.inspector.dto.InstanceTimeline;
import io.inspector.dto.InstanceTimeline.LiveJobState;
import io.inspector.dto.InstanceTimeline.TimelineActivity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Timeline tab. Moved from {@link InstanceDetailService} (issue #475). */
final class InstanceTimelineAssembler {

    private final InstanceDetailService host;

    InstanceTimelineAssembler(InstanceDetailService host) {
        this.host = host;
    }

    /* ================= timeline ================= */

    public InstanceTimeline timeline(String engineId, String instanceId) {
        EngineConfig engine = host.engineForRead(engineId);
        host.requireHistoric(engine, instanceId);
        int maxDepth = host.props.hierarchyMaxDepthOrDefault();
        // The cycle guard (R-TEST-07 doctrine): a real engine cannot produce a
        // calledProcessInstanceId cycle, but the visited set makes the recursion total anyway.
        Set<String> visited = new HashSet<>();
        visited.add(instanceId);
        // Global backstop over depth×breadth so a parallel-MI fan-out cannot flood the BFF.
        int[] budget = {InstanceDetailService.HIERARCHY_MAX_RENDERED_NODES};
        Level root = timelineLevel(engine, instanceId, 0, maxDepth, visited, budget, engine.maxPageSizeOrDefault());
        return new InstanceTimeline(root.activities(), root.total(), root.truncated());
    }

    /** The rows of ONE instance plus its own page total/truncation (fed to the parent's cap flag). */
    private record Level(List<TimelineActivity> activities, long total, boolean truncated) {}

    /**
     * Builds one instance's activity rows, recursively nesting each call-activity's called
     * instance as a sub-lane. Recursion is bounded three ways — depth ({@code maxDepth}),
     * breadth ({@code pageSize}, the hierarchy cap for children) and a global node
     * {@code budget} — and cycle-guarded on {@code visited} process-instance ids. Failing
     * nodes carry a live job state; a dead-lettered async node, whose history row rolled back
     * with its transaction, is synthesized from the runtime lanes (the phantom-node union).
     */
    private Level timelineLevel(
            EngineConfig engine,
            String instanceId,
            int depth,
            int maxDepth,
            Set<String> visited,
            int[] budget,
            int pageSize) {
        FlowablePage page =
                host.flowable.listHistoricActivities(engine, CallPriority.INTERACTIVE, instanceId, 0, pageSize);
        List<Map<String, Object>> rows = page.dataOrEmpty();
        boolean truncated = page.total() > rows.size();

        // Live failure is read from the runtime lanes (the source of truth), NOT from the
        // historic rows — an async dead-letter leaves no ACT_HI_ACTINST row at all.
        Map<String, LiveJobState> stateByActivity = liveJobStates(engine, instanceId);
        Set<String> annotated = new HashSet<>();

        List<TimelineActivity> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String activityId = InstanceDetailService.str(row, "activityId");
            LiveJobState state = null;
            if (activityId != null
                    && InstanceDetailService.str(row, "endTime") == null
                    && stateByActivity.containsKey(activityId)) {
                state = stateByActivity.get(activityId);
                annotated.add(activityId);
            }

            List<TimelineActivity> children = List.of();
            boolean capped = false;
            String childInstanceId = InstanceDetailService.str(row, "calledProcessInstanceId");
            if (childInstanceId != null) {
                if (depth >= maxDepth || budget[0] <= 0) {
                    capped = true; // depth or node-budget cap — the drill-out link still stands
                } else if (visited.add(childInstanceId)) {
                    budget[0]--;
                    Level child = timelineLevel(
                            engine,
                            childInstanceId,
                            depth + 1,
                            maxDepth,
                            visited,
                            budget,
                            InstanceDetailService.HIERARCHY_BREADTH_CAP);
                    children = child.activities();
                    capped = child.truncated(); // the child's own page was breadth-truncated
                }
                // else: already visited — cycle guard; leave the sub-lane unexpanded, link stands.
            }
            out.add(withSubLane(InstanceDetailService.timelineRow(row), state, capped, children));
        }

        // Phantom-node union: any live failure whose activity produced no annotated row is a
        // rolled-back async dead-letter — surface it so the failure is never invisible.
        for (Map.Entry<String, LiveJobState> entry : stateByActivity.entrySet()) {
            if (annotated.contains(entry.getKey())) continue;
            out.add(phantomNode(entry.getKey(), entry.getValue(), rows));
        }
        return new Level(out, page.total(), truncated);
    }

    /** activityId → live job state for one instance: dead-letter (FAILED) wins over failing (RETRYING). */
    private Map<String, LiveJobState> liveJobStates(EngineConfig engine, String instanceId) {
        Lanes lanes = host.scanInstanceLanes(engine, instanceId);
        if (lanes.deadLetter().isEmpty() && lanes.failing().isEmpty()) return Map.of();
        Map<String, LiveJobState> byActivity = new LinkedHashMap<>();
        for (Map<String, Object> job : lanes.failing()) {
            String activityId = host.failingActivityIdOf(engine, job);
            if (activityId != null) byActivity.putIfAbsent(activityId, LiveJobState.RETRYING);
        }
        for (Map<String, Object> job : lanes.deadLetter()) {
            String activityId = host.failingActivityIdOf(engine, job);
            if (activityId != null) byActivity.put(activityId, LiveJobState.FAILED); // override RETRYING
        }
        return byActivity;
    }

    /**
     * A synthesized bar for a failing activity with no historic row (async rollback). Borrows
     * the name/type from any sibling row sharing the activityId (a prior finished attempt);
     * otherwise the activityId stands alone. Unfinished by construction — it carries no time.
     */
    private static TimelineActivity phantomNode(String activityId, LiveJobState state, List<Map<String, Object>> rows) {
        String name = null;
        String type = null;
        for (Map<String, Object> row : rows) {
            if (activityId.equals(InstanceDetailService.str(row, "activityId"))) {
                name = InstanceDetailService.str(row, "activityName");
                type = InstanceDetailService.str(row, "activityType");
                break;
            }
        }
        return new TimelineActivity(
                null, activityId, name, type, null, null, null, null, null, null, null, state, false, List.of());
    }

    private static TimelineActivity withSubLane(
            TimelineActivity base, LiveJobState state, boolean capped, List<TimelineActivity> children) {
        return new TimelineActivity(
                base.id(),
                base.activityId(),
                base.activityName(),
                base.activityType(),
                base.executionId(),
                base.startTime(),
                base.endTime(),
                base.durationMs(),
                base.assignee(),
                base.taskId(),
                base.calledProcessInstanceId(),
                state,
                capped,
                children);
    }
}

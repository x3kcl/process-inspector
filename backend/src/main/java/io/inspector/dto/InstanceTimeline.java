package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/timeline — historic activity instances as duration
 * bars (SPEC §4), startTime ascending. {@code calledProcessInstanceId} links a
 * call-activity bar to its child instance (the sub-lane affordance). One page up to the
 * engine cap; {@code truncated} marks a longer history honestly (per-retry gaps are not
 * reconstructable from Flowable history and are deliberately not promised).
 */
public record InstanceTimeline(List<TimelineActivity> activities, long total, boolean truncated) {

    /** One historic activity instance — a bar on the Gantt. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimelineActivity(
            String id,
            String activityId,
            String activityName,
            String activityType,
            String executionId,
            String startTime,
            String endTime, // null = still running (the live-annotated bar)
            Long durationMs,
            String assignee, // user tasks
            String taskId,
            String calledProcessInstanceId) {} // call activities → child sub-lane
}

package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/timeline — historic activity instances as duration
 * bars (SPEC §4), startTime ascending. A call-activity bar nests the called instance's own
 * activities as a {@code children} sub-lane, recursed under the hierarchy caps (depth 10,
 * breadth 50/node, global node budget) with a {@code calledProcessInstanceId} cycle guard.
 * {@code truncated} marks a longer top-level history honestly (per-retry gaps are not
 * reconstructable from Flowable history and are deliberately not promised).
 */
public record InstanceTimeline(List<TimelineActivity> activities, long total, boolean truncated) {

    /** One historic activity instance — a bar on the Gantt, possibly with a child sub-lane. */
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
            String calledProcessInstanceId, // call activities → child sub-lane
            LiveJobState liveJobState, // FAILED/RETRYING joined from the runtime lanes; null = healthy
            boolean isCapped, // this node's sub-lane was truncated by ANY cap (breadth/depth/budget)
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<TimelineActivity> children) {}

    /**
     * Live runtime job state joined onto a failing/unfinished node — the FAILED/dead-letter
     * diagnostic (SPEC §4; R-SEM-01 glossary). A dead-lettered async node has no historic
     * row (its transaction rolled back) and is synthesized from the lanes; {@code null} on a
     * node means healthy or merely waiting, not failing.
     */
    public enum LiveJobState {
        /** A dead-letter job is parked on this node's execution — retries exhausted. */
        FAILED,
        /** A job carrying an exceptionMessage but with retries remaining — still retrying. */
        RETRYING
    }
}

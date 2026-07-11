package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id} — the Stage 2 vitals header (SPEC §4): everything the
 * operator sees WITHOUT a tab or a click. Historic-first, so a completed instance renders
 * vitals instead of 404ing (flowable-rest skill §2); {@code whyStuck} is present exactly
 * when the instance is FAILED or RETRYING; {@code waitingFor} names what an idle-but-alive
 * instance is blocked on (message/signal subscription, timer due date).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstanceDetail(
        String compositeId, // engineId:processInstanceId — the deep-link/copy-for-ticket key
        String engineId,
        String processInstanceId,
        String businessKey,
        String tenantId,
        String processDefinitionId,
        String definitionKey, // derived from processDefinitionId (key:version:uuid) — no
        String definitionName, // engine serializes key/version on historic rows (M2a)
        Integer definitionVersion,
        String startTime,
        String endTime, // null while running
        Long durationMs, // engine-reported for ended instances, else null
        String startedBy,
        InstanceStatusFlags flags,
        InstanceStatus status, // flags.primaryStatus(), serialized for the chip
        String superProcessInstanceId, // non-null on a call-activity child
        String telemetryUrl, // engine's telemetry-url-template rendered for THIS instance;
        // null when no template is configured (SPEC §4: absent template → no link)
        List<CurrentActivity> currentActivities, // unfinished activities; empty when ended
        WhyStuck whyStuck,
        List<WaitState> waitingFor,
        // Count of external-worker jobs on this instance (v1.x #7) — the fifth queue is not in
        // the four job lanes, so its presence is invisible without this. Null on a pre-6.8
        // engine (capability absent) or an ended instance; a count (incl. 0) when applicable.
        Integer externalWorkerJobs,
        // R-SAFE-05 point-of-action visibility (usability W3 sliver): true = in the protected
        // registry, so below the ADMIN floor every verb is disabled-with-reason and the vitals
        // header shows the protected badge. null = protection store unreachable (unknown — the
        // execution-time guard still refuses fail-closed either way, mirroring the search row).
        Boolean protectedInstance,
        // The L3-supplied protection reason, surfaced on the badge; null when not protected or
        // when the store was unreachable. Never a secret (reasons are operator-authored, audited).
        String protectionReason) {

    /** One unfinished activity — the "where is it" line + diagram token markers. */
    public record CurrentActivity(String activityId, String activityName, String activityType, String startTime) {}

    /**
     * The "why stuck" strip (SPEC §4): exception first line, failing activity, retries
     * state. {@code retriesRemaining}/{@code nextRetryDue} describe the RETRYING tier
     * ("attempt parked in the timer lane, fires at …"); dead-letter evidence means
     * retries are exhausted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WhyStuck(
            String exceptionFirstLine,
            String failingActivityId, // job elementId where the engine serializes it (6.8+)
            String failureTime, // newest failure-lane job createTime
            int deadLetterJobs,
            int retryingJobs,
            Integer retriesRemaining, // from the newest retrying job, null when dead-lettered
            String nextRetryDue) {} // that job's dueDate — "next retry 14:35"

    /** What a waiting instance waits FOR: a subscription or a timer (SPEC §4 vitals). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WaitState(
            String kind, // MESSAGE | SIGNAL | TIMER
            String name, // subscription event name; null for plain timers
            String activityId,
            String dueDate, // timers only
            String createdTime) {}
}

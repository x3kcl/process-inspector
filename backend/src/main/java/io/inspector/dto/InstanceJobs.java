package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/jobs — Flowable's four job queues kept DISTINCT
 * (SPEC §4: the lane IS the diagnosis): executable (ready/running), timer (scheduled —
 * including a failing job parked between retry attempts), suspended (follows instance
 * suspension), deadLetter (retries exhausted — the FAILED evidence). Stacktraces are
 * fetched on expand via {@code GET …/jobs/{jobId}/stacktrace?lane=}, never eagerly.
 */
public record InstanceJobs(
        List<JobDto> executable, List<JobDto> timer, List<JobDto> suspended, List<JobDto> deadLetter) {

    /** One raw job row, typed. Nullable fields differ by lane and engine version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobDto(
            String id,
            String lane, // EXECUTABLE | TIMER | SUSPENDED | DEADLETTER (echoed for flat use)
            String createTime,
            String dueDate, // timers: when it fires; a parked retry's next attempt
            Integer retries,
            String exceptionMessage, // first-line snippet; full stacktrace on expand
            String elementId, // failing/waiting activity (6.8+; null on 6.3-era engines)
            String elementName,
            String executionId,
            String processDefinitionId,
            String tenantId) {}
}

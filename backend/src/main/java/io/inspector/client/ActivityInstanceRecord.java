package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code /history/historic-activity-instances} — the typed wire shape of the
 * historic-activity endpoint family (#474). A taken sequence flow is a row too
 * ({@code activityType == "sequenceFlow"}); an unfinished activity has a null {@code endTime} and a
 * null {@code durationInMillis}. Times stay the engine's verbatim strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityInstanceRecord(
        String id,
        String activityId,
        String activityName,
        String activityType,
        String executionId,
        String processInstanceId,
        String processDefinitionId,
        String taskId,
        String calledProcessInstanceId,
        String assignee,
        String startTime,
        String endTime,
        Long durationInMillis,
        String tenantId) {}

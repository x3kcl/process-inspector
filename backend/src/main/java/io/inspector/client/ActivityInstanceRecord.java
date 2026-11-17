package io.inspector.client;

/** One historic activity instance as the history endpoint serialises it (#474). */
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

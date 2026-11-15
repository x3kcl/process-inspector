package io.inspector.client;

/**
 * One user task as {@code /runtime/tasks} or {@code /history/historic-task-instances} serialises
 * it — the typed wire shape of the tasks endpoint family (#474). The runtime row carries
 * {@code createTime}, {@code suspended} and {@code delegationState}; the historic row carries
 * {@code startTime}, {@code endTime} and {@code durationInMillis}. A field the leg does not
 * serialise is null.
 */
public record TaskRecord(
        String id,
        String name,
        String description,
        String taskDefinitionKey,
        String assignee,
        String owner,
        String category,
        Integer priority,
        String processInstanceId,
        String processDefinitionId,
        String executionId,
        String createTime,
        String startTime,
        String endTime,
        String dueDate,
        Long durationInMillis,
        String delegationState,
        Boolean suspended) {}

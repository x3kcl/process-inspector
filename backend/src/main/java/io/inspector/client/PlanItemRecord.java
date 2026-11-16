package io.inspector.client;

/** One runtime CMMN plan-item instance (#474). */
public record PlanItemRecord(
        String id,
        String name,
        String elementId,
        String planItemDefinitionId,
        String planItemDefinitionType,
        String state,
        Boolean stage,
        String stageInstanceId,
        String createTime,
        String lastAvailableTime,
        String lastEnabledTime,
        String lastStartedTime,
        String completedTime,
        String occurredTime,
        String terminatedTime,
        String exitTime,
        String endedTime) {}

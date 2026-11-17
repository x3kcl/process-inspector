package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One runtime plan-item instance of {@code /cmmn-runtime/plan-item-instances} (#474). Three ids,
 * never interchangeable: {@code id} is the instance (the join key of a CMMN job's
 * {@code planItemInstanceId}), {@code elementId} the CMMN DI shape key, and
 * {@code planItemDefinitionId} the plan-item definition (the migration pre-check key).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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

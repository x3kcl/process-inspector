package io.inspector.client;

/** One event subscription as the runtime endpoint serialises it (#474). */
public record EventSubscriptionRecord(
        String id,
        String eventType,
        String eventName,
        String activityId,
        String executionId,
        String processInstanceId,
        String processDefinitionId,
        String created,
        String configuration,
        String tenantId) {}

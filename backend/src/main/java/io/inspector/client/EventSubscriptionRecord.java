package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code /runtime/event-subscriptions} — the typed wire shape of the event-subscription
 * endpoint family (#474). A start subscription (message/signal start event) carries no
 * {@code processInstanceId} / {@code executionId}; {@code eventType} is lower case on the wire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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

package io.inspector.catalog;

import java.util.List;

/**
 * Event subscriptions for one process definition. A row with a null {@code processInstanceId}
 * is a start subscription. {@code complete} is false when the engine reported more rows than
 * the page we kept.
 */
public record DefinitionSubscriptionsResponse(String scope, List<SubscriptionRow> subscriptions, boolean complete) {

    public record SubscriptionRow(
            String id,
            String eventType,
            String eventName,
            String activityId,
            String processInstanceId,
            boolean startSubscription) {}
}

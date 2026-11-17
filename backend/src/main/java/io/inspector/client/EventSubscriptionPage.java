package io.inspector.client;

import java.util.List;

/** One page of a EventSubscriptionRecord list, typed (#474). */
public record EventSubscriptionPage(List<EventSubscriptionRecord> data, long total, int start, int size) {
    public static EventSubscriptionPage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<EventSubscriptionRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}

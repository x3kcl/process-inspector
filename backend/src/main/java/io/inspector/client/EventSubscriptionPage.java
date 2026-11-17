package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of EventSubscriptionRecord rows, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSubscriptionPage(List<EventSubscriptionRecord> data, long total, int start, int size) {
    public static EventSubscriptionPage empty() {
        return new EventSubscriptionPage(List.of(), 0, 0, 0);
    }

    public List<EventSubscriptionRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

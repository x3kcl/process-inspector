package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of ActivityInstanceRecord rows, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityInstancePage(List<ActivityInstanceRecord> data, long total, int start, int size) {
    public static ActivityInstancePage empty() {
        return new ActivityInstancePage(List.of(), 0, 0, 0);
    }

    public List<ActivityInstanceRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

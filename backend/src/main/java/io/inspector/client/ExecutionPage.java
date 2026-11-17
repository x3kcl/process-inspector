package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of ExecutionRecord rows, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionPage(List<ExecutionRecord> data, long total, int start, int size) {
    public static ExecutionPage empty() {
        return new ExecutionPage(List.of(), 0, 0, 0);
    }

    public List<ExecutionRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

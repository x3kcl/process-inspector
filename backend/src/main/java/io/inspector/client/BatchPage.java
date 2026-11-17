package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a batch list, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchPage(List<BatchRecord> data, long total, int start, int size) {
    public static BatchPage empty() {
        return new BatchPage(List.of(), 0, 0, 0);
    }

    public List<BatchRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

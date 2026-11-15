package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a process-instance list or query, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessInstancePage(List<ProcessInstanceRecord> data, long total, int start, int size) {
    public static ProcessInstancePage empty() {
        return new ProcessInstancePage(List.of(), 0, 0, 0);
    }

    public List<ProcessInstanceRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

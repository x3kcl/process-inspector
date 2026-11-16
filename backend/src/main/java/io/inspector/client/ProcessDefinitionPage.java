package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a process-definition list, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessDefinitionPage(List<ProcessDefinitionRecord> data, long total, int start, int size) {
    public static ProcessDefinitionPage empty() {
        return new ProcessDefinitionPage(List.of(), 0, 0, 0);
    }

    public List<ProcessDefinitionRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

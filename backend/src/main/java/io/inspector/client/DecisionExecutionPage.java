package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of historic DMN decision executions, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionExecutionPage(List<DecisionExecutionRecord> data, long total, int start, int size) {
    public List<DecisionExecutionRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

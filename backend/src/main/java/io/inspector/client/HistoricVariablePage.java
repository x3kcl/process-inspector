package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of historic variable instances, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoricVariablePage(List<HistoricVariableRecord> data, long total, int start, int size) {
    public static HistoricVariablePage empty() {
        return new HistoricVariablePage(List.of(), 0, 0, 0);
    }

    public List<HistoricVariableRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

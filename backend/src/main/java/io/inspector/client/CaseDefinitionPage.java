package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a case-definition list, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseDefinitionPage(List<CaseDefinitionRecord> data, long total, int start, int size) {
    public static CaseDefinitionPage empty() {
        return new CaseDefinitionPage(List.of(), 0, 0, 0);
    }

    public List<CaseDefinitionRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

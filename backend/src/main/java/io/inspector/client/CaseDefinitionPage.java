package io.inspector.client;

import java.util.List;

/** One page of a case-definition list (#474). */
public record CaseDefinitionPage(List<CaseDefinitionRecord> data, long total, int start, int size) {
    public static CaseDefinitionPage empty() {
        return new CaseDefinitionPage(List.of(), 0, 0, 0);
    }

    public List<CaseDefinitionRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

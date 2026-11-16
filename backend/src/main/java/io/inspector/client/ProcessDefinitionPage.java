package io.inspector.client;

import java.util.List;

/** One page of a process-definition list, typed (#474). */
public record ProcessDefinitionPage(List<ProcessDefinitionRecord> data, long total, int start, int size) {
    public static ProcessDefinitionPage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<ProcessDefinitionRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}

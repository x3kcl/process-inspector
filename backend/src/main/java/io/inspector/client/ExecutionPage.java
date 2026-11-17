package io.inspector.client;

import java.util.List;

/** One page of a ExecutionRecord list, typed (#474). */
public record ExecutionPage(List<ExecutionRecord> data, long total, int start, int size) {
    public static ExecutionPage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<ExecutionRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}

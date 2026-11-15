package io.inspector.client;

import java.util.List;

/** One page of a process-instance list or query (runtime or historic), typed (#474). */
public record ProcessInstancePage(List<ProcessInstanceRecord> data, long total, int start, int size) {
    public static ProcessInstancePage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<ProcessInstanceRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}

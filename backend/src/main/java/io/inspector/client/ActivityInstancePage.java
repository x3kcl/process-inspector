package io.inspector.client;

import java.util.List;

/** One page of a ActivityInstanceRecord list, typed (#474). */
public record ActivityInstancePage(List<ActivityInstanceRecord> data, long total, int start, int size) {
    public static ActivityInstancePage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<ActivityInstanceRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}

package io.inspector.client;

import java.util.List;

/** One page of a batch list (#474). */
public record BatchPage(List<BatchRecord> data, long total, int start, int size) {
    public static BatchPage empty() {
        return new BatchPage(List.of(), 0, 0, 0);
    }

    public List<BatchRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

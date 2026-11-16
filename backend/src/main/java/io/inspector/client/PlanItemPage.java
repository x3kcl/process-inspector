package io.inspector.client;

import java.util.List;

/** One page of a plan-item list, typed (#474). */
public record PlanItemPage(List<PlanItemRecord> data, long total, int start, int size) {
    public static PlanItemPage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<PlanItemRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}

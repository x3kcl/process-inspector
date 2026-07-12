package io.inspector.client;

import java.util.List;
import java.util.Map;

/**
 * Every Flowable list/query endpoint answers {data:[…], total, start, size, …}; we keep entries
 * untyped here and map them in the aggregation layer. Shared by every {@code *ApiClient} facade
 * (Engine-client split, #86).
 */
public record FlowablePage(List<Map<String, Object>> data, long total, int start, int size) {
    public static FlowablePage empty() {
        return new FlowablePage(List.of(), 0, 0, 0);
    }

    public List<Map<String, Object>> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

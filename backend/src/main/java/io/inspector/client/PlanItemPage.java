package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a plan-item list, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanItemPage(List<PlanItemRecord> data, long total, int start, int size) {
    public static PlanItemPage empty() {
        return new PlanItemPage(List.of(), 0, 0, 0);
    }

    public List<PlanItemRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

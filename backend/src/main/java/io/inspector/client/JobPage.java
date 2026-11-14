package io.inspector.client;

import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a job lane, typed (#474). */
public record JobPage(List<JobRecord> data, long total, int start, int size) {
    public static JobPage empty() {
        return new JobPage(List.of(), 0, 0, 0);
    }

    public List<JobRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

package io.inspector.client;

import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a task list (runtime or historic), typed (#474). */
public record TaskPage(List<TaskRecord> data, long total, int start, int size) {
    public static TaskPage empty() {
        return new TaskPage(List.of(), 0, 0, 0);
    }

    public List<TaskRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}

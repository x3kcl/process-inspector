package io.inspector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Test fixture: build typed task rows from the wire-keyed maps the tests already read as JSON. */
public final class TaskRecords {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskRecords() {}

    public static TaskRecord task(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, TaskRecord.class);
    }

    public static List<TaskRecord> tasks(List<? extends Map<String, ?>> wire) {
        return wire.stream().map(TaskRecords::task).toList();
    }

    public static TaskPage page(List<? extends Map<String, ?>> wire) {
        return new TaskPage(tasks(wire), wire.size(), 0, wire.size());
    }
}

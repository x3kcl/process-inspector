package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the task-verb characterization ({@code TaskVerbBodyWireShapeTest}, #720) reaches the two
 * client calls whose body parameter changes type. Kept out of the frozen RED file so the test
 * states inputs and expected bytes only. This builds the insertion-ordered maps
 * {@code CorrectiveActionService} sends on {@code main}.
 */
final class TaskVerbWire {

    private TaskVerbWire() {}

    /** {@code variables} rows are {@code {name, type, value}}; a null type is left out, a null value is sent. */
    static void complete(
            ProcessApiClient client, EngineConfig engine, CallPriority priority, String taskId, Object[]... variables) {
        List<Map<String, Object>> typed = new ArrayList<>();
        for (Object[] row : variables) {
            Map<String, Object> variable = new LinkedHashMap<>();
            variable.put("name", row[0]);
            if (row[1] != null) {
                variable.put("type", row[1]);
            }
            variable.put("value", row[2]);
            typed.add(variable);
        }
        client.completeTask(engine, priority, taskId, typed);
    }

    /** The update-task patch: a null field is left unchanged (absent), an empty string clears it (null). */
    static void update(
            ProcessApiClient client,
            EngineConfig engine,
            CallPriority priority,
            String taskId,
            String name,
            String description,
            String owner,
            String dueDate,
            String category,
            Integer taskPriority) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "name", name);
        put(body, "description", description);
        put(body, "owner", owner);
        put(body, "dueDate", dueDate);
        put(body, "category", category);
        if (taskPriority != null) {
            body.put("priority", taskPriority);
        }
        client.putTask(engine, priority, taskId, body);
    }

    private static void put(Map<String, Object> body, String key, String value) {
        if (value != null) {
            body.put(key, value.isBlank() ? null : value);
        }
    }
}

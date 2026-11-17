package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.TaskActionRequest.Variable;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * How the task-verb characterization ({@code TaskVerbBodyWireShapeTest}, #720) reaches the two
 * client calls whose body parameter changes type. Kept out of the frozen RED file so the test
 * states inputs and expected bytes only. Before the typed records this built the insertion-ordered
 * maps {@code CorrectiveActionService} sent on {@code main}; now it builds the records.
 */
final class TaskVerbWire {

    private TaskVerbWire() {}

    /** {@code variables} rows are {@code {name, type, value}}; a null type is left out, a null value is sent. */
    static void complete(
            ProcessApiClient client, EngineConfig engine, CallPriority priority, String taskId, Object[]... variables) {
        List<Variable> typed = new ArrayList<>();
        for (Object[] row : variables) {
            typed.add(new Variable((String) row[0], (String) row[1], row[2]));
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
        client.putTask(
                engine,
                priority,
                taskId,
                new TaskUpdateRequest(
                        null,
                        field(name),
                        field(description),
                        field(owner),
                        field(dueDate),
                        field(category),
                        taskPriority));
    }

    private static TaskField field(String value) {
        return value == null ? null : new TaskField(value.isBlank() ? null : value);
    }
}

package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code /history/historic-variable-instances} (#474): the attribution plus the variable
 * envelope nested under {@code variable} — on 6.3, 6.8 and 7.1 alike (6.3 sends no
 * {@code executionId} and a null nested {@code scope}). {@code taskId} is null on anything but a
 * task-local variable; a process-scope row's {@code executionId} is the instance id.
 *
 * <p>{@code name}/{@code type}/{@code value} on the row itself keep the pre-typing fallback: a row
 * without a nested {@code variable} is read from the row ({@link #envelope()}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoricVariableRecord(
        String id,
        String processInstanceId,
        String executionId,
        String taskId,
        VariableRecord variable,
        String name,
        String type,
        Object value) {

    public HistoricVariableRecord(
            String id, String processInstanceId, String executionId, String taskId, VariableRecord variable) {
        this(id, processInstanceId, executionId, taskId, variable, null, null, null);
    }

    /** The nested envelope, or — on a flat row — the envelope read from the row itself. */
    public VariableRecord envelope() {
        return variable != null ? variable : new VariableRecord(name, type, value, null, null);
    }
}

package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code /history/historic-variable-instances} (#474): the attribution plus the variable
 * envelope nested under {@code variable} — on 6.8 and 7.1 alike. {@code taskId} is null on anything
 * but a task-local variable; a process-scope row's {@code executionId} is the instance id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoricVariableRecord(
        String id, String processInstanceId, String executionId, String taskId, VariableRecord variable) {}

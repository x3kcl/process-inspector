package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;

/**
 * How the variable-write characterization ({@code VariableWriteBodyWireShapeTest}, #720) reaches
 * the two process client calls whose body parameter changes type. Kept out of the frozen RED file
 * so the test states inputs and expected bytes only. Before the typed record this built the
 * insertion-ordered maps {@code CorrectiveActionService} sent on {@code main} for EDIT_VARIABLE;
 * now it builds the record.
 */
final class VariableWriteWire {

    private VariableWriteWire() {}

    /** Process-instance scope. A null type is left out, a null value is sent. */
    static void instance(
            ProcessApiClient client,
            EngineConfig engine,
            CallPriority priority,
            String processInstanceId,
            String name,
            String type,
            Object value) {
        client.putInstanceVariable(
                engine, priority, processInstanceId, name, VariableWriteRequest.of(name, type, value));
    }

    /** Execution-local (step-local) scope: the same body plus {@code scope:"local"} as the last key. */
    static void local(
            ProcessApiClient client,
            EngineConfig engine,
            CallPriority priority,
            String executionId,
            String name,
            String type,
            Object value) {
        client.putExecutionVariable(engine, priority, executionId, name, VariableWriteRequest.local(name, type, value));
    }
}

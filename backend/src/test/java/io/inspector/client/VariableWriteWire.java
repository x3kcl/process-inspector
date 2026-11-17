package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How the variable-write characterization ({@code VariableWriteBodyWireShapeTest}, #720) reaches
 * the two process client calls whose body parameter changes type. Kept out of the frozen RED file
 * so the test states inputs and expected bytes only. It builds the insertion-ordered maps
 * {@code CorrectiveActionService} sends on {@code main} for EDIT_VARIABLE.
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
        client.putInstanceVariable(engine, priority, processInstanceId, name, body(name, type, value));
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
        Map<String, Object> body = body(name, type, value);
        body.put("scope", "local");
        client.putExecutionVariable(engine, priority, executionId, name, body);
    }

    private static Map<String, Object> body(String name, String type, Object value) {
        Map<String, Object> typed = new LinkedHashMap<>();
        typed.put("name", name);
        if (type != null) {
            typed.put("type", type);
        }
        typed.put("value", value);
        return typed;
    }
}

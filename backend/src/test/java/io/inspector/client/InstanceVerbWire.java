package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the instance-verb characterization ({@code InstanceVerbBodyWireShapeTest}, #720) reaches the
 * two client calls whose body parameter changes type. Kept out of the frozen RED file so the test
 * states inputs and expected bytes only. This copy builds the bodies exactly as {@code
 * FlowSurgeryService} does on {@code main}: insertion-ordered maps.
 */
final class InstanceVerbWire {

    private InstanceVerbWire() {}

    static void changeState(
            ProcessApiClient client,
            EngineConfig engine,
            CallPriority priority,
            String processInstanceId,
            List<String> cancelActivityIds,
            List<String> startActivityIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cancelActivityIds", cancelActivityIds);
        body.put("startActivityIds", startActivityIds);
        client.changeActivityState(engine, priority, processInstanceId, body);
    }

    /** {@code variables} rows are {@code {name, type, value}}; a null business key and no rows are left out. */
    static void start(
            ProcessApiClient client,
            EngineConfig engine,
            CallPriority priority,
            boolean pinVersion,
            String definitionRef,
            String businessKey,
            Object[]... variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(pinVersion ? "processDefinitionId" : "processDefinitionKey", definitionRef);
        if (businessKey != null) {
            body.put("businessKey", businessKey);
        }
        List<Map<String, Object>> typed = new ArrayList<>();
        for (Object[] row : variables) {
            Map<String, Object> variable = new LinkedHashMap<>();
            variable.put("name", row[0]);
            variable.put("type", row[1]);
            variable.put("value", row[2]);
            typed.add(variable);
        }
        if (!typed.isEmpty()) {
            body.put("variables", typed);
        }
        client.startProcessInstance(engine, priority, body);
    }
}

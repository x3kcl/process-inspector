package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.StartProcessInstanceRequest.Variable;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * How the instance-verb characterization ({@code InstanceVerbBodyWireShapeTest}, #720) reaches the
 * two client calls whose body parameter changes type. Kept out of the frozen RED file so the test
 * states inputs and expected bytes only. Before the typed records this built the insertion-ordered
 * maps {@code FlowSurgeryService} sent on {@code main}; now it builds the records.
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
        client.changeActivityState(
                engine,
                priority,
                processInstanceId,
                new ChangeActivityStateRequest(cancelActivityIds, startActivityIds));
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
        List<Variable> typed = new ArrayList<>();
        for (Object[] row : variables) {
            typed.add(new Variable((String) row[0], (String) row[1], row[2]));
        }
        client.startProcessInstance(
                engine,
                priority,
                pinVersion
                        ? StartProcessInstanceRequest.pinned(definitionRef, businessKey, typed)
                        : StartProcessInstanceRequest.latest(definitionRef, businessKey, typed));
    }
}

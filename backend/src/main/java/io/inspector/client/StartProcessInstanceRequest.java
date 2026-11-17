package io.inspector.client;

import java.util.List;

/** The body of POST /runtime/process-instances. RED stub (#720): the factories carry nothing yet. */
public record StartProcessInstanceRequest(
        String processDefinitionId, String processDefinitionKey, String businessKey, List<Variable> variables) {

    /** One carried variable; the value stays whatever the engine handed out. */
    public record Variable(String name, String type, Object value) {}

    public static StartProcessInstanceRequest pinned(
            String processDefinitionId, String businessKey, List<Variable> variables) {
        return new StartProcessInstanceRequest(null, null, null, null);
    }

    public static StartProcessInstanceRequest latest(
            String processDefinitionKey, String businessKey, List<Variable> variables) {
        return new StartProcessInstanceRequest(null, null, null, null);
    }
}

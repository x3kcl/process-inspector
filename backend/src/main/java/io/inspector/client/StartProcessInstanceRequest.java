package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The body of POST /runtime/process-instances — restart-as-new (#720). Exactly one definition
 * reference is set: {@link #pinned} (the id, version pinned) or {@link #latest} (the key). A null
 * reference or business key and an empty variable list are OMITTED, never sent as null / [].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartProcessInstanceRequest(
        String processDefinitionId,
        String processDefinitionKey,
        String businessKey,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Variable> variables) {

    /** One carried variable. The value stays whatever the engine handed out; a null value is SENT. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Variable(String name, String type, Object value) {}

    public static StartProcessInstanceRequest pinned(
            String processDefinitionId, String businessKey, List<Variable> variables) {
        return new StartProcessInstanceRequest(processDefinitionId, null, businessKey, variables);
    }

    public static StartProcessInstanceRequest latest(
            String processDefinitionKey, String businessKey, List<Variable> variables) {
        return new StartProcessInstanceRequest(null, processDefinitionKey, businessKey, variables);
    }
}

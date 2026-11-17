package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The body of PUT /runtime/executions/{id} (#720) — deliver what a waiting execution is blocked
 * on: {@code action}, then at most one name. The name that does not apply is OMITTED. A null name
 * is refused before any engine byte — as the map body did.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionActionRequest(String action, String messageName, String signalName) {

    public static ExecutionActionRequest message(String messageName) {
        return new ExecutionActionRequest(
                "messageEventReceived", Objects.requireNonNull(messageName, "messageName"), null);
    }

    public static ExecutionActionRequest signal(String signalName) {
        return new ExecutionActionRequest(
                "signalEventReceived", null, Objects.requireNonNull(signalName, "signalName"));
    }

    public static ExecutionActionRequest trigger() {
        return new ExecutionActionRequest("trigger", null, null);
    }
}

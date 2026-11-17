package io.inspector.client;

/** The body of PUT /runtime/executions/{id} (#720). Stub: the typed record lands with the refactor. */
public record ExecutionActionRequest(String action, String messageName, String signalName) {

    public static ExecutionActionRequest message(String messageName) {
        return new ExecutionActionRequest(null, null, null);
    }

    public static ExecutionActionRequest signal(String signalName) {
        return new ExecutionActionRequest(null, null, null);
    }

    public static ExecutionActionRequest trigger() {
        return new ExecutionActionRequest(null, null, null);
    }
}

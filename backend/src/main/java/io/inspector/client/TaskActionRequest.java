package io.inspector.client;

import java.util.List;

/** The body of POST /runtime/tasks/{id}. RED stub (#720): the factories carry nothing yet. */
public record TaskActionRequest(String action, TaskField assignee, List<Variable> variables) {

    /** One variable handed over on complete; the value stays whatever the operator sent. */
    public record Variable(String name, String type, Object value) {}

    public static TaskActionRequest complete(List<Variable> variables) {
        return new TaskActionRequest(null, null, null);
    }

    public static TaskActionRequest of(String action, String assignee) {
        return new TaskActionRequest(null, null, null);
    }
}

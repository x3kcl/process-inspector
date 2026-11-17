package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The body of POST /runtime/tasks/{id} — complete, claim, delegate, resolve (#720). A null
 * {@code assignee} and an empty variable list are OMITTED; an unclaim carries
 * {@code "assignee":null} through a {@link TaskField}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskActionRequest(
        String action,
        TaskField assignee,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Variable> variables) {

    /** One variable handed over on complete. A null type is omitted; a null value is SENT. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Variable(
            String name,
            @JsonInclude(JsonInclude.Include.NON_NULL) String type,
            Object value) {}

    public static TaskActionRequest complete(List<Variable> variables) {
        return new TaskActionRequest("complete", null, variables);
    }

    /** Claim always sends {@code assignee}, including null (unclaim). Any other action omits a null one. */
    public static TaskActionRequest of(String action, String assignee) {
        return new TaskActionRequest(
                action, assignee != null || "claim".equals(action) ? new TaskField(assignee) : null, null);
    }
}

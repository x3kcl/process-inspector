package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of PUT /runtime/tasks/{id} (#720). Only the keys present are changed: a null component
 * is OMITTED, a {@link TaskField} holding null clears the field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskUpdateRequest(
        TaskField assignee,
        TaskField name,
        TaskField description,
        TaskField owner,
        TaskField dueDate,
        TaskField category,
        Integer priority) {

    /** Reassign, or clear the assignee with null (return to team). The key is always sent. */
    public static TaskUpdateRequest assignee(String assignee) {
        return new TaskUpdateRequest(new TaskField(assignee), null, null, null, null, null, null);
    }
}

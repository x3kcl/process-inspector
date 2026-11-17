package io.inspector.client;

/** The body of PUT /runtime/tasks/{id}. RED stub (#720): carries nothing yet. */
public record TaskUpdateRequest(
        TaskField assignee,
        TaskField name,
        TaskField description,
        TaskField owner,
        TaskField dueDate,
        TaskField category,
        Integer priority) {

    public TaskUpdateRequest {
        assignee = null;
        name = null;
        description = null;
        owner = null;
        dueDate = null;
        category = null;
        priority = null;
    }

    public static TaskUpdateRequest assignee(String assignee) {
        return new TaskUpdateRequest(null, null, null, null, null, null, null);
    }
}

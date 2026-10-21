package io.inspector.action;

import java.util.List;

/**
 * The request body of {@code POST …/actions/{verb}} — a flat union across the M4 verb
 * catalog; each verb validates the fields it needs (400 with the missing field named).
 * {@code reason} discipline per SPEC §6: tiers ≥2 always, tier 1 required on prod
 * engines; ≥10 chars whenever present. {@code confirmToken} is the tier-3 prod typed
 * token (target-specific — business key / job id / definition key, never a generic yes).
 * {@code assignee} is the reassign-task target user id; unassign-task ignores it (clears
 * the assignee → the task falls back to its candidate groups). {@code task} is the
 * update-task patch (a null field is left alone). {@code link} is an identity-link edit.
 */
public record ActionRequest(
        String reason,
        String ticketId,
        String confirmToken,
        String jobId,
        String taskId,
        String executionId,
        VariableEdit variable,
        List<TypedVariable> variables,
        EventTrigger event,
        Boolean includeProcessInstances,
        String assignee,
        String dueDate,
        String jobLane,
        TaskPatch task,
        IdentityLink link) {

    /** Pre-#486 body. task and link stay null. */
    public ActionRequest(
            String reason,
            String ticketId,
            String confirmToken,
            String jobId,
            String taskId,
            String executionId,
            VariableEdit variable,
            List<TypedVariable> variables,
            EventTrigger event,
            Boolean includeProcessInstances,
            String assignee,
            String dueDate,
            String jobLane) {
        this(
                reason,
                ticketId,
                confirmToken,
                jobId,
                taskId,
                executionId,
                variable,
                variables,
                event,
                includeProcessInstances,
                assignee,
                dueDate,
                jobLane,
                null,
                null);
    }

    /** Pre-#485 body. dueDate and jobLane stay null. */
    public ActionRequest(
            String reason,
            String ticketId,
            String confirmToken,
            String jobId,
            String taskId,
            String executionId,
            VariableEdit variable,
            List<TypedVariable> variables,
            EventTrigger event,
            Boolean includeProcessInstances,
            String assignee) {
        this(
                reason,
                ticketId,
                confirmToken,
                jobId,
                taskId,
                executionId,
                variable,
                variables,
                event,
                includeProcessInstances,
                assignee,
                null,
                null);
    }

    public static ActionRequest empty() {
        return new ActionRequest(null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Compare-and-set variable edit (R-SEM-09): the request carries what the operator saw.
     * {@code executionId} scopes the edit: {@code null}/blank = process (case) scope — the
     * default; a non-blank value = the execution-local ("step-local") variable on that
     * execution node (SPEC §4a, multi-instance loop locals). The scoped read, CAS pre-check
     * and write all follow the executionId when present.
     */
    public record VariableEdit(String name, String type, Object value, Object expectedOldValue, String executionId) {

        /** Process-scope edit (no execution target) — the pre-step-local call shape. */
        public VariableEdit(String name, String type, Object value, Object expectedOldValue) {
            this(name, type, value, expectedOldValue, null);
        }
    }

    /** Typed engine variable — the declared type is preserved on the wire (flowable-rest §2). */
    public record TypedVariable(String name, String type, Object value) {}

    /**
     * update-task patch. A null component is left unchanged. An empty string clears a string
     * field. {@code dueDate} is an ISO-8601 instant when set.
     */
    public record TaskPatch(
            String name, String description, String owner, String dueDate, Integer priority, String category) {}

    /** Identity link on a task. {@code family} is {@code users} or {@code groups}. */
    public record IdentityLink(String family, String identityId, String type) {}

    /** unstick-event payload: what the waiting execution should receive. */
    public record EventTrigger(String type, String name) {
        public static final String MESSAGE = "message";
        public static final String SIGNAL = "signal";
        public static final String TRIGGER = "trigger";
    }
}

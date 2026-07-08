package io.inspector.action;

import java.util.List;

/**
 * The request body of {@code POST …/actions/{verb}} — a flat union across the M4 verb
 * catalog; each verb validates the fields it needs (400 with the missing field named).
 * {@code reason} discipline per SPEC §6: tiers ≥2 always, tier 1 required on prod
 * engines; ≥10 chars whenever present. {@code confirmToken} is the tier-3 prod typed
 * token (target-specific — business key / job id / definition key, never a generic yes).
 * {@code assignee} is the reassign-task target user id; unassign-task ignores it (clears
 * the assignee → the task falls back to its candidate groups).
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
        String assignee) {

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

    /** unstick-event payload: what the waiting execution should receive. */
    public record EventTrigger(String type, String name) {
        public static final String MESSAGE = "message";
        public static final String SIGNAL = "signal";
        public static final String TRIGGER = "trigger";
    }
}

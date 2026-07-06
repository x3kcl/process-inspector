package io.inspector.action;

import java.util.UUID;

/**
 * The mutation may or may not have reached the engine (timeout after dispatch, connection
 * dropped mid-flight). Outcome recorded {@code unknown}; NEVER auto-retried — a blind
 * retry can double-fire (corrective-actions skill §4). The operator verifies state first.
 */
public class OutcomeUnknownException extends RuntimeException {

    private final UUID auditId;

    public OutcomeUnknownException(UUID auditId, Throwable cause) {
        super(
                "Action dispatched — outcome unknown. The engine did not answer within the write budget."
                        + " The action may have been applied: re-check the instance state before retrying anything.",
                cause);
        this.auditId = auditId;
    }

    public UUID auditId() {
        return auditId;
    }
}

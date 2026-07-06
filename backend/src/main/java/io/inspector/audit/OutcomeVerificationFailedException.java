package io.inspector.audit;

import java.util.UUID;

/**
 * The dual-write post-flight failure (R-SEM-18): the ENGINE CALL SUCCEEDED but the audit
 * outcome UPDATE failed. The token moved — this must never render as a generic 500. The
 * audit row stays PENDING and is swept to {@code unknown} by the reconciler; the operator
 * re-checks instance state ("Verify now", R-SAFE-09) instead of re-firing.
 */
public class OutcomeVerificationFailedException extends RuntimeException {

    private final UUID auditId;

    public OutcomeVerificationFailedException(UUID auditId, Throwable cause) {
        super(
                "Action dispatched — outcome verification failed. The engine accepted the action, but the"
                        + " audit outcome could not be recorded. Re-check the instance state before doing anything"
                        + " else; do NOT blindly retry.",
                cause);
        this.auditId = auditId;
    }

    public UUID auditId() {
        return auditId;
    }
}

package io.inspector.audit;

/**
 * The fail-closed refusal (R-AUD-01): the audit INSERT did not commit, so the mutation
 * was never sent to the engine. Nothing happened — the error copy must say so and name
 * Postgres (OPERATIONS §6).
 */
public class AuditUnavailableException extends RuntimeException {

    public AuditUnavailableException(Throwable cause) {
        super(
                "Refused fail-closed: the audit store (Postgres) is unavailable, so the action was NOT sent"
                        + " to the engine. Nothing happened. Restore the inspector database and retry.",
                cause);
    }
}

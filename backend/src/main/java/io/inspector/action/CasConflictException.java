package io.inspector.action;

import java.util.UUID;

/**
 * Compare-and-set mismatch on edit-variable (R-SEM-09): the engine's current value is not
 * the {@code expectedOldValue} the operator saw. Refused pre-flight — nothing changed on
 * the engine; the response carries the fresh value for the UI's re-render.
 */
public class CasConflictException extends RuntimeException {

    private final UUID auditId;
    private final Object currentValue;
    private final Object expectedOldValue;

    public CasConflictException(UUID auditId, String variableName, Object currentValue, Object expectedOldValue) {
        super("Variable '" + variableName + "' changed since you loaded it — the edit was NOT applied."
                + " Review the fresh value and retry deliberately.");
        this.auditId = auditId;
        this.currentValue = currentValue;
        this.expectedOldValue = expectedOldValue;
    }

    public UUID auditId() {
        return auditId;
    }

    public Object currentValue() {
        return currentValue;
    }

    public Object expectedOldValue() {
        return expectedOldValue;
    }
}

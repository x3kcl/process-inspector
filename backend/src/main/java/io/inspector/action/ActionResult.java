package io.inspector.action;

import java.util.UUID;

/**
 * Successful verb outcome. {@code deltaStatement} is the explicit state delta the guard
 * ladder demands for the outcome toast ("Job 8123 moved to executable queue; retries
 * reset") — never a bare "success". {@code auditId} links the toast to the audit row.
 * {@code newJobId} is set only when the engine replaced the job (timer reschedule on 7.x,
 * #744) and the replacement was resolved; null otherwise.
 */
public record ActionResult(
        UUID auditId,
        String correlationId,
        String outcome,
        Integer engineHttpStatus,
        String deltaStatement,
        String newJobId) {

    public ActionResult(
            UUID auditId, String correlationId, String outcome, Integer engineHttpStatus, String deltaStatement) {
        this(auditId, correlationId, outcome, engineHttpStatus, deltaStatement, null);
    }
}

package io.inspector.api;

import io.inspector.action.CasConflictException;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.OutcomeVerificationFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * Maps the action-layer failure modes onto problem-detail responses whose copy keeps the
 * SPEC §6 three-way distinction visible: refused pre-flight (nothing happened) / engine
 * rejected (nothing happened, engine's words quoted) / dispatched-unverified (assume it
 * happened until verified). The {@code code} property is machine-readable for the UI.
 */
@RestControllerAdvice
public class ActionExceptionHandler {

    @ExceptionHandler(GuardRefusedException.class)
    ProblemDetail guardRefused(GuardRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle("Refused pre-flight — nothing happened");
        problem.setProperty("code", e.code());
        problem.setProperty("outcome", "refused");
        return problem;
    }

    /** Fail-closed (R-AUD-01): tier-independent in this implementation — no audit, no action. */
    @ExceptionHandler(AuditUnavailableException.class)
    ProblemDetail auditUnavailable(AuditUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Refused fail-closed — audit store unavailable");
        problem.setProperty("code", "audit-unavailable");
        problem.setProperty("outcome", "refused");
        return problem;
    }

    @ExceptionHandler(CasConflictException.class)
    ProblemDetail casConflict(CasConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Compare-and-set conflict — the edit was not applied");
        problem.setProperty("code", "cas-conflict");
        problem.setProperty("outcome", "refused");
        problem.setProperty("auditId", e.auditId());
        problem.setProperty("currentValue", e.currentValue());
        problem.setProperty("expectedOldValue", e.expectedOldValue());
        return problem;
    }

    @ExceptionHandler(EngineRejectedException.class)
    ProblemDetail engineRejected(EngineRejectedException e) {
        HttpStatus status = e.engineStatus() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle("Engine rejected the action — nothing happened");
        problem.setProperty("code", "engine-rejected");
        problem.setProperty("outcome", "failed");
        problem.setProperty("auditId", e.auditId());
        problem.setProperty("engineStatus", e.engineStatus());
        problem.setProperty("engineBody", e.engineBody());
        return problem;
    }

    /** R-SEM-18: the timeout leg — dispatched (maybe), unverified, never auto-retried. */
    @ExceptionHandler(OutcomeUnknownException.class)
    ProblemDetail outcomeUnknown(OutcomeUnknownException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, e.getMessage());
        problem.setTitle("Action dispatched — outcome unknown");
        problem.setProperty("code", "outcome-unknown");
        problem.setProperty("outcome", "unknown");
        problem.setProperty("auditId", e.auditId());
        return problem;
    }

    /** R-SEM-18: engine succeeded, audit close failed — the specialized error, NOT a generic 500. */
    @ExceptionHandler(OutcomeVerificationFailedException.class)
    ProblemDetail outcomeVerificationFailed(OutcomeVerificationFailedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        problem.setTitle("Action dispatched — outcome verification failed");
        problem.setProperty("code", "outcome-verification-failed");
        problem.setProperty("outcome", "unknown");
        problem.setProperty("auditId", e.auditId());
        return problem;
    }

    /**
     * Benign SSE lifecycle noise (live-ui-sse doctrine): an emitter hitting its window is
     * just a reconnect — the stream is already committed, so there is nothing to answer
     * and nothing worth an error log.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    void sseWindowElapsed() {
        // intentionally empty — EventSource reconnects on its own
    }
}

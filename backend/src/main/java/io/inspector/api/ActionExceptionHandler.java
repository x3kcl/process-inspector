package io.inspector.api;

import io.inspector.action.CasConflictException;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.OutcomeVerificationFailedException;
import io.inspector.bulk.BulkCountDriftException;
import io.inspector.security.reauth.ReauthRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maps the action-layer failure modes onto problem-detail responses whose copy keeps the
 * SPEC §6 three-way distinction visible: refused pre-flight (nothing happened) / engine
 * rejected (nothing happened, engine's words quoted) / dispatched-unverified (assume it
 * happened until verified). The {@code code} property is machine-readable for the UI.
 *
 * <p>The two generic handlers below (issue #87 — F4, "one error contract") close the gap for
 * everything that ISN'T a domain-specific action failure: a bad request param
 * ({@link IllegalArgumentException}, previously three DIFFERENT ad-hoc {@code {"error":…}} map
 * shapes hand-rolled per controller) and every plain {@link ResponseStatusException} throw site
 * across the app (previously falling through to the container {@code /error} path and Spring's
 * bare {@code {timestamp,status,error,path}} shape). Both messages are safe to surface verbatim —
 * every call site in this codebase constructs them as deliberate, developer-authored, client-facing
 * copy (never a raw exception/stack message) — see {@code io.inspector.api.ProblemCodes} for the
 * fallback {@code code} slug used when the exception carries none of its own.
 */
@RestControllerAdvice
public class ActionExceptionHandler {

    /** A caller-supplied value the BFF rejects pre-flight — 400, never a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Bad request");
        problem.setProperty("code", "bad-request");
        return problem;
    }

    /**
     * The catch-all for every plain {@code throw new ResponseStatusException(status, "…")} site —
     * NOT the action-layer's own typed exceptions above, which already carry a domain
     * {@code code}. {@code getReason()} is the message each call site passed explicitly; a status
     * with no reason falls back to the status's own reason phrase (never {@code null} detail).
     */
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail statusException(ResponseStatusException e) {
        HttpStatus known = HttpStatus.resolve(e.getStatusCode().value());
        String detail = e.getReason() != null
                ? e.getReason()
                : known != null ? known.getReasonPhrase() : e.getStatusCode().toString();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getStatusCode(), detail);
        problem.setProperty("code", ProblemCodes.fromStatus(e.getStatusCode()));
        return problem;
    }

    @ExceptionHandler(GuardRefusedException.class)
    ProblemDetail guardRefused(GuardRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle("Refused pre-flight — nothing happened");
        problem.setProperty("code", e.code());
        problem.setProperty("outcome", "refused");
        return problem;
    }

    /**
     * Dangerous-set freshness challenge (IDP-SECURITY.md §5, R-SAFE-07): the session is authenticated
     * but too stale to run this verb. A 401 — not a 403 — because the remedy is to re-authenticate;
     * the {@code reauth-required} code + the {@code X-Reauth-Required} header let the SPA tell a
     * freshness challenge apart from a plain "session expired" 401 (which redirects to full sign-in),
     * so it can run the interstitial and replay the verb. Thrown BEFORE the audit gate — nothing
     * happened, nothing recorded (a refusal, like rbac-denied).
     */
    @ExceptionHandler(ReauthRequiredException.class)
    ResponseEntity<ProblemDetail> reauthRequired(ReauthRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Re-authentication required — nothing happened");
        problem.setProperty("code", "reauth-required");
        problem.setProperty("outcome", "refused");
        problem.setProperty("freshnessWindowSeconds", e.freshnessWindowSeconds());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("X-Reauth-Required", "true")
                .body(problem);
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

    /** Tier-4 destructive-bulk wizard (issue #100): the typed count no longer matches the fresh scope. */
    @ExceptionHandler(BulkCountDriftException.class)
    ProblemDetail bulkCountDrift(BulkCountDriftException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Scope drifted since preview — nothing happened");
        problem.setProperty("code", "bulk-count-drift");
        problem.setProperty("outcome", "refused");
        problem.setProperty("confirmedCount", e.confirmedCount());
        problem.setProperty("actualCount", e.actualCount());
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

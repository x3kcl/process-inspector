package io.inspector.action;

import org.springframework.http.HttpStatus;

/**
 * A pre-flight guard refusal (SPEC §6): NOTHING was sent to the engine. The code names
 * the specific gate (rbac-denied, engine-read-only, instance-protected, reason-required,
 * confirm-token-mismatch, …) so the UI can grey-with-reason instead of guessing.
 */
public class GuardRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GuardRefusedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

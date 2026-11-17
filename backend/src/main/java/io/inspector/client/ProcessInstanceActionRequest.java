package io.inspector.client;

import java.util.Objects;

/**
 * The body of PUT /runtime/process-instances/{id} — {@code suspend} or {@code activate} (#720).
 * A null action is refused here, before any engine byte — as the map body did.
 */
public record ProcessInstanceActionRequest(String action) {

    public ProcessInstanceActionRequest {
        Objects.requireNonNull(action, "action");
    }
}

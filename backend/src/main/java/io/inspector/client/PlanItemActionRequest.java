package io.inspector.client;

import java.util.Objects;

/**
 * The body of PUT …/cmmn-runtime/plan-item-instances/{id} — {@code trigger}, {@code enable} or
 * {@code disable} (#720). A null action is refused before any engine byte — as the map body did.
 */
public record PlanItemActionRequest(String action) {

    public PlanItemActionRequest {
        Objects.requireNonNull(action, "action");
    }
}

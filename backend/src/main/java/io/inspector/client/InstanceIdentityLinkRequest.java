package io.inspector.client;

import java.util.Objects;

/**
 * The body of POST …/process-instances/{id}/identitylinks and …/case-instances/{id}/identitylinks
 * (#720): {@code user}, then {@code type}. Users only — Flowable 6.8.0 and 7.1.0 answer 400 to a
 * {@code group} here and to {@code userId}. A null is refused before any engine byte — as the map
 * body did.
 */
public record InstanceIdentityLinkRequest(String user, String type) {

    public InstanceIdentityLinkRequest {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(type, "type");
    }
}

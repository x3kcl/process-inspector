package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of POST /runtime/tasks/{id}/identitylinks (#720): {@code user} or {@code group}, then
 * {@code type}. Flowable 6.3.1, 6.8.0 and 7.1.0 answer 400 to {@code userId} / {@code groupId} (#763).
 */
public record TaskIdentityLinkRequest(
        @JsonInclude(JsonInclude.Include.NON_NULL) String user,
        @JsonInclude(JsonInclude.Include.NON_NULL) String group,
        String type) {

    /** {@code family} is users or groups. */
    public static TaskIdentityLinkRequest of(String family, String identityId, String type) {
        return "groups".equals(family)
                ? new TaskIdentityLinkRequest(null, identityId, type)
                : new TaskIdentityLinkRequest(identityId, null, type);
    }
}

package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The body of POST /runtime/tasks/{id}/identitylinks (#720): {@code userId} or {@code groupId}, then {@code type}. */
public record TaskIdentityLinkRequest(
        @JsonInclude(JsonInclude.Include.NON_NULL) String userId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String groupId,
        String type) {

    /** {@code family} is users or groups. */
    public static TaskIdentityLinkRequest of(String family, String identityId, String type) {
        return "groups".equals(family)
                ? new TaskIdentityLinkRequest(null, identityId, type)
                : new TaskIdentityLinkRequest(identityId, null, type);
    }
}

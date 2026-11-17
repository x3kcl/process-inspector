package io.inspector.client;

/** The body of POST /runtime/tasks/{id}/identitylinks. RED stub (#720): the factory carries nothing yet. */
public record TaskIdentityLinkRequest(String userId, String groupId, String type) {

    public static TaskIdentityLinkRequest of(String family, String identityId, String type) {
        return new TaskIdentityLinkRequest(null, null, null);
    }
}

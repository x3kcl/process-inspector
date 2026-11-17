package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One identity link as {@code /runtime/process-instances/{id}/identitylinks},
 * {@code /runtime/tasks/{id}/identitylinks} or {@code /history/historic-process-instances/{id}/identitylinks}
 * serialises it — the typed wire shape of the identity-link family (#474). The runtime legs name the
 * identity {@code user}/{@code group}; the historic leg names it {@code userId}/{@code groupId} and adds
 * {@code processInstanceId}/{@code taskId}. A name the leg does not serialise is null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityLinkRecord(
        String user,
        String userId,
        String group,
        String groupId,
        String type,
        String taskId,
        String processInstanceId) {}

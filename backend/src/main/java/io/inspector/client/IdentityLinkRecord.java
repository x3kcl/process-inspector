package io.inspector.client;

/** RED stub (#474) — replaced by the typed identity-link row. */
public record IdentityLinkRecord(
        String user,
        String userId,
        String group,
        String groupId,
        String type,
        String taskId,
        String processInstanceId) {}

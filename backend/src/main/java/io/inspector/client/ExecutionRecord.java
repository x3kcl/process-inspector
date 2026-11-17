package io.inspector.client;

/** One execution as the runtime execution endpoints serialise it (#474). */
public record ExecutionRecord(
        String id,
        String parentId,
        String processInstanceId,
        String superExecutionId,
        String activityId,
        Boolean suspended,
        String tenantId) {}

package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One execution as {@code /runtime/executions} (list and by id) serialises it — the typed wire
 * shape of the executions endpoint family (#474). The root execution is the process instance
 * itself: no {@code parentId}, no {@code activityId}. A field the engine does not serialise is
 * null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionRecord(
        String id,
        String parentId,
        String processInstanceId,
        String superExecutionId,
        String activityId,
        Boolean suspended,
        String tenantId) {}

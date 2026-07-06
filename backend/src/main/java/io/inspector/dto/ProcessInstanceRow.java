package io.inspector.dto;

import io.inspector.dto.SearchRequest.InstanceStatus;

/**
 * One normalized results-grid row. compositeId ({@code engineId:processInstanceId})
 * is the instance's identity everywhere outside a single-engine call.
 *
 * <p>{@code status} is the derived primary chip ({@link InstanceStatusFlags#primaryStatus()});
 * {@code flags} carries the full collision-capable truth (SPEC §3). {@code failureTime} is
 * the newest dead-letter job {@code createTime} for the instance — for a root flagged
 * {@code failedInSubprocess} it is inherited from the failing child. {@code scopeType} is
 * {@code "bpmn"} for every v1 row (carried from day one for CMMN support, SPEC §3).
 */
public record ProcessInstanceRow(
        String compositeId,
        String engineId,
        String engineName,
        String engineColor,
        String processInstanceId,
        String businessKey,
        String processDefinitionKey,
        String processDefinitionName,
        Integer definitionVersion,
        String tenantId,
        String scopeType,
        InstanceStatus status,
        InstanceStatusFlags flags,
        String startTime,
        String endTime,
        String failureTime,
        String currentActivityOrError, // dead-letter/failing-job exception snippet when present
        /**
         * R-SAFE-05 marker for the bulk bar: true = in the protected registry (bulk
         * auto-excludes it, badge shown). null = protection store unreachable (unknown —
         * the BFF guard still refuses at execution time either way).
         */
        Boolean protectedInstance) {

    public ProcessInstanceRow withProtected(boolean isProtected) {
        return new ProcessInstanceRow(
                compositeId,
                engineId,
                engineName,
                engineColor,
                processInstanceId,
                businessKey,
                processDefinitionKey,
                processDefinitionName,
                definitionVersion,
                tenantId,
                scopeType,
                status,
                flags,
                startTime,
                endTime,
                failureTime,
                currentActivityOrError,
                isProtected);
    }
}

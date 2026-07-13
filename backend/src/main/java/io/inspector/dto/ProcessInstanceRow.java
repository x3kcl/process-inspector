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
 *
 * <p>{@code superProcessInstanceId} (usability W2 #7, R-UXQ-12): the parent instance id when this
 * row is a call-activity CHILD, straight off the historic wire row (every search leg assembles from
 * historic queries, which always serialize it for children) — the grid's root-vs-child marker.
 * {@code null} = a root instance.
 */
public record ProcessInstanceRow(
        String compositeId,
        String engineId,
        String engineName,
        String engineColor,
        String processInstanceId,
        String businessKey,
        String superProcessInstanceId,
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
        Boolean protectedInstance,
        /**
         * Status honesty (#166, mirrors {@code InstanceDetail#terminationReason}, #118/#105):
         * {@code status} stays COMPLETED even for a terminated/deleted instance (the 5-value
         * chip set + search facets/counts must not churn, SPEC §3) — this carries the engine's
         * termination reason so the grid can render a TERMINATED chip instead of a misleading
         * COMPLETED one, exactly like the detail page already does. Null for a genuine
         * completion (and while running).
         */
        String terminationReason) {

    public ProcessInstanceRow withProtected(boolean isProtected) {
        return new ProcessInstanceRow(
                compositeId,
                engineId,
                engineName,
                engineColor,
                processInstanceId,
                businessKey,
                superProcessInstanceId,
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
                isProtected,
                terminationReason);
    }
}

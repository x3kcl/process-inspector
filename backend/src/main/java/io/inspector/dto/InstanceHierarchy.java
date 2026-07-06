package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/hierarchy — the call-activity tree, BOTH directions
 * (SPEC §4): the requested instance is first walked UP the {@code superProcessInstanceId}
 * chain to its root, then the tree renders DOWN from that root. Bounded by doctrine:
 * max depth (default 10) and max 50 children rendered per node (R-SEM-19) — counts stay
 * exact via the query total ({@code childTotal}), rendering stays bounded, and every cap
 * is an explicit marker, never a silent truncation.
 */
public record InstanceHierarchy(
        String requestedProcessInstanceId,
        String rootProcessInstanceId,
        HierarchyNode root,
        boolean depthLimitReached, // true when un-rendered deeper levels provably exist
        int maxDepth,
        int breadthCap) {

    /** One instance node. Child failures surface as {@code hasDeadLetterJobs} per node. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HierarchyNode(
            String processInstanceId,
            String businessKey,
            String definitionKey,
            String definitionName,
            Integer definitionVersion,
            String startTime,
            String endTime,
            boolean ended,
            boolean hasDeadLetterJobs,
            boolean requested, // marks the instance the operator navigated from
            long childTotal, // exact engine-reported child count (may exceed the render cap)
            boolean childrenTruncated, // childTotal > rendered children ("+N more — not shown")
            // Self-referential collections defeat springdoc's inference (they emit
            // unknown[]); a $ref — not implementation=, which recurses to StackOverflow —
            // keeps the generated TS contract recursive.
            @ArraySchema(schema = @Schema(ref = "#/components/schemas/HierarchyNode"))
            List<HierarchyNode> children) {}
}

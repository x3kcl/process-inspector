package io.inspector.migration;

import io.inspector.migration.HierarchyTree.PlanCircularReference;
import io.inspector.migration.HierarchyTree.PlanNode;
import io.inspector.migration.HierarchyTree.PlanTruncation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stored hierarchy migration plan (#497) as the operator sees it: the running call-activity
 * tree under the root(s), one group per source definition with its decision, and whether the plan
 * can be submitted.
 *
 * @param groups one per source definition id in the tree, in first-seen (top-down) order
 * @param levels the per-depth roll-up; execution runs the deepest level first
 * @param truncations where the walk stopped short of the real tree; any entry makes the plan
 *     non-executable
 * @param circularReferences nodes whose definition key is also an ancestor's. Reported, not
 *     skipped: the instance tree is still a tree, so these instances stay in the plan and are
 *     ordered by their own depth
 * @param executableCount instances that will be sent — the count a prod submit must type
 * @param notSentCount instances of configured groups the analysis blocked; each is listed in the
 *     job as {@code not_run} and blocks its parent
 * @param notExecutableReason null when the plan can be submitted
 */
public record HierarchyPlanView(
        UUID id,
        String engineId,
        String createdBy,
        Instant createdAt,
        List<String> rootInstanceIds,
        List<PlanNode> nodes,
        List<PlanGroupView> groups,
        List<PlanLevelView> levels,
        List<PlanTruncation> truncations,
        List<PlanCircularReference> circularReferences,
        int maxDepth,
        int breadthCap,
        int nodeCap,
        int executableCount,
        int notSentCount,
        boolean executable,
        String notExecutableReason,
        String banner) {

    public static final String BANNER =
            "Children first, then parent. A parent is migrated only after every planned child reached its target;"
                    + " a child that failed, is unknown, is protected, moved since the estimate or was not sent"
                    + " leaves its parent not run."
                    + " Each instance is still one engine call with its own audit row — there is no cross-instance"
                    + " transaction, so a partial result is possible and is reported per instance.";

    /**
     * @param status {@code PENDING} (no decision), {@code ANALYZING} (configured, the estimate is
     *     still running), {@code CONFIGURED}, {@code ANALYSIS_FAILED} (configure again) or
     *     {@code SKIPPED} (the operator leaves these instances on their version; they are not in
     *     the job and do not block their parent)
     */
    public record PlanGroupView(
            String definitionId,
            String definitionKey,
            Integer version,
            int instanceCount,
            int minDepth,
            int maxDepth,
            String status,
            UUID analysisId,
            String targetDefinitionId,
            int executableCount,
            int blockerCount) {}

    public record PlanLevelView(int depth, int instanceCount, int executableCount) {}
}

package io.inspector.detail;

import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceHierarchy;
import io.inspector.dto.InstanceHierarchy.HierarchyNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hierarchy tab. Moved from {@link InstanceDetailService} (issue #475). */
final class InstanceHierarchyAssembler {

    private final InstanceDetailService host;

    InstanceHierarchyAssembler(InstanceDetailService host) {
        this.host = host;
    }

    /* ================= hierarchy — bounded, both directions ================= */

    public InstanceHierarchy hierarchy(String engineId, String instanceId) {
        EngineConfig engine = host.engineForRead(engineId);
        Map<String, Object> requested = host.requireHistoric(engine, instanceId);
        int maxDepth = host.props.hierarchyMaxDepthOrDefault();

        // UP: walk the superProcessInstanceId chain to the root — one historic GET per
        // ancestor, cycle-guarded, depth-capped (a real engine cannot produce a cycle;
        // the guard is doctrine, R-TEST-07).
        Map<String, Object> root = requested;
        Set<String> visited = new HashSet<>();
        visited.add(instanceId);
        for (int depth = 0; depth < maxDepth; depth++) {
            String parentId = InstanceDetailService.str(root, "superProcessInstanceId");
            if (parentId == null || !visited.add(parentId)) break;
            Map<String, Object> parent =
                    host.flowable.getHistoricProcessInstance(engine, CallPriority.INTERACTIVE, parentId);
            if (parent == null) break; // parent purged from history — current node is the root
            root = parent;
        }
        String rootId = InstanceDetailService.str(root, "id");

        // DOWN: BFS from the root. Children come from ONE historic query per node
        // (superProcessInstanceId filter), rendered up to the breadth cap; the query total
        // keeps counts exact beyond it (count-only doctrine, R-SEM-19).
        Map<String, NodeBuild> builds = new LinkedHashMap<>();
        NodeBuild rootBuild = new NodeBuild(root, 0);
        builds.put(rootId, rootBuild);
        Deque<NodeBuild> queue = new ArrayDeque<>();
        queue.add(rootBuild);
        boolean depthLimitReached = false;
        while (!queue.isEmpty()) {
            NodeBuild node = queue.poll();
            FlowablePage page = childrenOf(engine, node.id());
            node.childTotal = page.total();
            if (node.depth >= maxDepth) {
                depthLimitReached |= page.total() > 0;
                continue;
            }
            for (Map<String, Object> child : page.dataOrEmpty()) {
                if (node.children.size() >= InstanceDetailService.HIERARCHY_BREADTH_CAP
                        || builds.size() >= InstanceDetailService.HIERARCHY_MAX_RENDERED_NODES) {
                    break;
                }
                String childId = InstanceDetailService.str(child, "id");
                if (childId == null || builds.containsKey(childId)) continue; // cycle guard
                NodeBuild childBuild = new NodeBuild(child, node.depth + 1);
                builds.put(childId, childBuild);
                node.children.add(childBuild);
                queue.add(childBuild);
            }
        }

        // Child failures surface per node (SPEC §4) — count-only DLQ membership per
        // rendered node, bounded by the render caps above.
        for (NodeBuild node : builds.values()) {
            node.hasDeadLetterJobs = host.deadLetterCount(engine, node.id()) > 0;
        }
        return new InstanceHierarchy(
                instanceId,
                rootId,
                rootBuild.toDto(instanceId),
                depthLimitReached,
                maxDepth,
                InstanceDetailService.HIERARCHY_BREADTH_CAP);
    }

    FlowablePage childrenOf(EngineConfig engine, String parentInstanceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("superProcessInstanceId", parentInstanceId);
        body.put("size", Math.min(InstanceDetailService.HIERARCHY_BREADTH_CAP, engine.maxPageSizeOrDefault()));
        InstanceDetailService.withTenant(engine, body);
        return host.flowable.queryHistoricProcessInstances(engine, CallPriority.INTERACTIVE, body);
    }

    /** Mutable BFS scaffold; the immutable DTO is assembled bottom-up at the end. */
    private static final class NodeBuild {
        final Map<String, Object> row;
        final int depth;
        final List<NodeBuild> children = new ArrayList<>();
        long childTotal;
        boolean hasDeadLetterJobs;

        NodeBuild(Map<String, Object> row, int depth) {
            this.row = row;
            this.depth = depth;
        }

        String id() {
            return InstanceDetailService.str(row, "id");
        }

        HierarchyNode toDto(String requestedId) {
            String definitionId = InstanceDetailService.str(row, "processDefinitionId");
            return new HierarchyNode(
                    id(),
                    InstanceDetailService.str(row, "businessKey"),
                    InstanceDetailService.definitionKeyOf(definitionId),
                    InstanceDetailService.str(row, "processDefinitionName"),
                    InstanceDetailService.definitionVersionOf(definitionId),
                    InstanceDetailService.str(row, "startTime"),
                    InstanceDetailService.str(row, "endTime"),
                    InstanceDetailService.str(row, "endTime") != null,
                    hasDeadLetterJobs,
                    id().equals(requestedId),
                    childTotal,
                    childTotal > children.size(),
                    children.stream().map(c -> c.toDto(requestedId)).toList());
        }
    }
}

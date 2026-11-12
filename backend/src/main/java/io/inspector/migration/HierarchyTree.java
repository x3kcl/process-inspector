package io.inspector.migration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded walk behind a hierarchy migration plan (#497): breadth-first from the root(s) down
 * the call-activity tree, ONE child read per visited node, so the read count is bounded by the
 * node cap however wide a level is. A 10k-child multi-instance call activity costs one read at
 * its parent and is reported as a truncation with the exact total, not enumerated.
 *
 * <p>No I/O here: the caller supplies the child read (the Hierarchy tab's own per-node query).
 */
public final class HierarchyTree {

    private HierarchyTree() {}

    /** One running instance as the child read returns it. */
    record Member(String instanceId, String definitionId, String businessKey) {}

    /** One page of running children plus the engine's exact total. */
    record Page(List<Member> rows, long total) {}

    @FunctionalInterface
    interface ChildReader {
        Page runningChildrenOf(String instanceId);
    }

    record Caps(int maxDepth, int breadth, int nodes) {}

    /** {@code parentInstanceId} is null for a root; {@code depth} 0 is a root. */
    public record PlanNode(
            String instanceId, String parentInstanceId, int depth, String definitionId, String businessKey) {}

    /**
     * Where the walk stopped short of the real tree. {@code kind}: {@code breadth} (a node has more
     * running children than one read returns), {@code depth} (children exist below the depth cap),
     * {@code nodes} (the node cap was reached), {@code roots} (the root cohort is wider than the
     * cap) or {@code instance-cycle} (an instance id came back under a second parent — not possible
     * on a real engine, refused rather than ordered). {@code total} is the engine's exact count,
     * {@code enumerated} how many of them are in the plan.
     */
    public record PlanTruncation(String kind, String instanceId, long total, int enumerated) {}

    /** A node whose definition key is also the key of one of its ancestors. */
    public record PlanCircularReference(
            String definitionKey, String instanceId, String ancestorInstanceId, int depth) {}

    record Walk(
            List<PlanNode> nodes, List<PlanTruncation> truncations, List<PlanCircularReference> circularReferences) {}

    /** A node while the walk runs: its parent can still change (a cohort root found under another root). */
    private static final class Seen {
        final Member member;
        String parentId;
        final int walkDepth;

        Seen(Member member, String parentId, int walkDepth) {
            this.member = member;
            this.parentId = parentId;
            this.walkDepth = walkDepth;
        }
    }

    static Walk walk(List<Member> roots, long rootTotal, ChildReader reader, Caps caps) {
        Map<String, Seen> byId = new LinkedHashMap<>();
        List<PlanTruncation> truncations = new ArrayList<>();
        Deque<Seen> queue = new ArrayDeque<>();
        for (Member root : roots) {
            if (byId.size() >= caps.nodes()) {
                break;
            }
            if (root.instanceId() == null || byId.containsKey(root.instanceId())) {
                continue;
            }
            Seen seen = new Seen(root, null, 0);
            byId.put(root.instanceId(), seen);
            queue.add(seen);
        }
        if (rootTotal > byId.size()) {
            truncations.add(new PlanTruncation("roots", null, rootTotal, byId.size()));
        }
        boolean full = false;
        while (!queue.isEmpty() && !full) {
            Seen node = queue.poll();
            String nodeId = node.member.instanceId();
            Page page = reader.runningChildrenOf(nodeId);
            if (page == null || page.total() == 0) {
                continue;
            }
            if (node.walkDepth >= caps.maxDepth()) {
                truncations.add(new PlanTruncation("depth", nodeId, page.total(), 0));
                continue;
            }
            int taken = 0;
            int cycles = 0;
            for (Member child : page.rows()) {
                if (taken >= caps.breadth()) {
                    break;
                }
                if (child.instanceId() == null) {
                    continue;
                }
                Seen known = byId.get(child.instanceId());
                if (known != null) {
                    // A cohort root that is also a descendant of another root (a recursive
                    // definition) hangs under its real parent. Anything else is a cycle.
                    if (known.parentId == null && !isAncestor(child.instanceId(), nodeId, byId)) {
                        known.parentId = nodeId;
                        taken++;
                    } else {
                        truncations.add(new PlanTruncation("instance-cycle", child.instanceId(), 1, 0));
                        cycles++;
                    }
                    continue;
                }
                if (byId.size() >= caps.nodes()) {
                    full = true;
                    break;
                }
                Seen added = new Seen(child, nodeId, node.walkDepth + 1);
                byId.put(child.instanceId(), added);
                queue.add(added);
                taken++;
            }
            if (full) {
                truncations.add(new PlanTruncation("nodes", nodeId, page.total(), taken));
            } else if (page.total() > taken + cycles) {
                truncations.add(new PlanTruncation("breadth", nodeId, page.total(), taken));
            }
        }

        // Depths from the final parent links: a re-parented root moves down with its subtree.
        Map<String, PlanNode> nodes = new LinkedHashMap<>();
        for (Seen seen : byId.values()) {
            int depth = 0;
            for (Seen up = byId.get(seen.parentId); up != null; up = byId.get(up.parentId)) {
                depth++;
            }
            PlanNode node = new PlanNode(
                    seen.member.instanceId(),
                    seen.parentId,
                    depth,
                    seen.member.definitionId(),
                    seen.member.businessKey());
            nodes.put(node.instanceId(), node);
            if (depth > caps.maxDepth()) {
                truncations.add(new PlanTruncation("depth", node.instanceId(), 1, 0));
            }
        }
        List<PlanCircularReference> circular = new ArrayList<>();
        for (PlanNode node : nodes.values()) {
            PlanNode ancestor = ancestorWithSameKey(node, nodes);
            if (ancestor != null) {
                circular.add(new PlanCircularReference(
                        keyOf(node.definitionId()), node.instanceId(), ancestor.instanceId(), node.depth()));
            }
        }
        return new Walk(List.copyOf(nodes.values()), List.copyOf(truncations), List.copyOf(circular));
    }

    /** Is {@code candidate} the node {@code of} itself or one of its ancestors. */
    private static boolean isAncestor(String candidate, String of, Map<String, Seen> byId) {
        for (String id = of; id != null; id = byId.get(id) == null ? null : byId.get(id).parentId) {
            if (id.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static PlanNode ancestorWithSameKey(PlanNode node, Map<String, PlanNode> byId) {
        String key = keyOf(node.definitionId());
        if (key == null || node.parentInstanceId() == null) {
            return null;
        }
        PlanNode current = byId.get(node.parentInstanceId());
        while (current != null) {
            if (key.equals(keyOf(current.definitionId()))) {
                return current;
            }
            current = current.parentInstanceId() == null ? null : byId.get(current.parentInstanceId());
        }
        return null;
    }

    /** {@code key:version:id} → {@code key}. */
    static String keyOf(String definitionId) {
        if (definitionId == null) {
            return null;
        }
        int i = definitionId.indexOf(':');
        return i > 0 ? definitionId.substring(0, i) : definitionId;
    }

    static Integer versionOf(String definitionId) {
        if (definitionId == null) {
            return null;
        }
        String[] parts = definitionId.split(":", 3);
        try {
            return parts.length >= 2 ? Integer.valueOf(parts[1]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

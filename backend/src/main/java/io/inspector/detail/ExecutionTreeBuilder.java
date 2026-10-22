package io.inspector.detail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a parent/child execution tree from one Flowable page. Breadth past the cap is counted, not dropped silently. */
public final class ExecutionTreeBuilder {

    public static final int BREADTH_CAP = 50;

    private ExecutionTreeBuilder() {}

    public record ExecutionNode(
            String id, String parentId, String activityId, boolean suspended, int childTotal, boolean capped) {}

    /** Flat list so the OpenAPI schema stays non-recursive. The UI nests on {@code parentId}. */
    public record ExecutionTree(List<ExecutionNode> nodes, boolean pageCapped, int breadthCap) {}

    public static ExecutionTree build(List<Map<String, Object>> rows, boolean pageCapped) {
        Map<String, Mutable> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String id = str(row, "id");
            if (id == null) {
                continue;
            }
            byId.put(id, new Mutable(id, str(row, "parentId"), str(row, "activityId"), bool(row, "suspended")));
        }
        List<Mutable> roots = new ArrayList<>();
        for (Mutable node : byId.values()) {
            Mutable parent = node.parentId == null ? null : byId.get(node.parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        List<ExecutionNode> nodes = new ArrayList<>();
        append(roots, nodes);
        return new ExecutionTree(nodes, pageCapped, BREADTH_CAP);
    }

    private static void append(List<Mutable> nodes, List<ExecutionNode> out) {
        int shown = Math.min(nodes.size(), BREADTH_CAP);
        for (int i = 0; i < shown; i++) {
            Mutable node = nodes.get(i);
            out.add(new ExecutionNode(
                    node.id,
                    node.parentId,
                    node.activityId,
                    node.suspended,
                    node.children.size(),
                    node.children.size() > BREADTH_CAP));
            append(node.children, out);
        }
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<String, Object> row, String key) {
        return Boolean.TRUE.equals(row.get(key));
    }

    private static final class Mutable {
        final String id;
        final String parentId;
        final String activityId;
        final boolean suspended;
        final List<Mutable> children = new ArrayList<>();

        Mutable(String id, String parentId, String activityId, boolean suspended) {
            this.id = id;
            this.parentId = parentId;
            this.activityId = activityId;
            this.suspended = suspended;
        }
    }
}

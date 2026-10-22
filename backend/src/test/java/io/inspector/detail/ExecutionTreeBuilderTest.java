package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionTreeBuilderTest {

    @Test
    void nestsChildrenAndCapsBreadth() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(Map.of("id", "root", "activityId", "start"));
        for (int i = 0; i < 52; i++) {
            rows.add(Map.of("id", "c" + i, "parentId", "root", "activityId", "task", "suspended", false));
        }

        ExecutionTreeBuilder.ExecutionTree tree = ExecutionTreeBuilder.build(rows, true);

        assertThat(tree.pageCapped()).isTrue();
        ExecutionTreeBuilder.ExecutionNode root = tree.nodes().stream()
                .filter(node -> node.id().equals("root"))
                .findFirst()
                .orElseThrow();
        assertThat(root.childTotal()).isEqualTo(52);
        assertThat(root.capped()).isTrue();
        assertThat(tree.nodes().stream().filter(node -> "root".equals(node.parentId())))
                .hasSize(ExecutionTreeBuilder.BREADTH_CAP);
    }
}

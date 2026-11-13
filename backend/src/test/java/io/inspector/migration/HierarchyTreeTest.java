package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.migration.HierarchyTree.Caps;
import io.inspector.migration.HierarchyTree.Member;
import io.inspector.migration.HierarchyTree.Page;
import io.inspector.migration.HierarchyTree.PlanNode;
import io.inspector.migration.HierarchyTree.PlanTruncation;
import io.inspector.migration.HierarchyTree.Walk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Rung 1: the bounded walk behind a hierarchy plan (#497) — pure, no engine. */
class HierarchyTreeTest {

    private static final Caps CAPS = new Caps(10, 50, 500);

    private final Map<String, Page> children = new HashMap<>();
    private final AtomicInteger reads = new AtomicInteger();

    private Walk walk(Caps caps, Member... roots) {
        return HierarchyTree.walk(
                List.of(roots),
                roots.length,
                id -> {
                    reads.incrementAndGet();
                    return children.getOrDefault(id, new Page(List.of(), 0));
                },
                caps);
    }

    private static Member member(String id, String key) {
        return new Member(id, key + ":1:def-" + key, "bk-" + id);
    }

    private void childrenOf(String parent, Member... rows) {
        children.put(parent, new Page(List.of(rows), rows.length));
    }

    @Test
    void threeLevelsComeBackTopDownWithParentAndDepth() {
        childrenOf("root", member("a", "hierA"));
        childrenOf("a", member("b", "hierB"));

        Walk walk = walk(CAPS, member("root", "hierRoot"));

        assertThat(walk.nodes())
                .extracting(PlanNode::instanceId, PlanNode::parentInstanceId, PlanNode::depth)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("root", null, 0),
                        org.assertj.core.groups.Tuple.tuple("a", "root", 1),
                        org.assertj.core.groups.Tuple.tuple("b", "a", 2));
        assertThat(walk.truncations()).isEmpty();
        assertThat(walk.circularReferences()).isEmpty();
        assertThat(reads.get()).isEqualTo(3); // one child read per node, leaves included
    }

    @Test
    void aTenThousandChildCallActivityCostsOneReadAndIsReportedNotEnumerated() {
        List<Member> page = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            page.add(member("mi-" + i, "wideChild"));
        }
        children.put("root", new Page(page, 10_000));

        Walk walk = walk(CAPS, member("root", "wideParent"));

        assertThat(walk.nodes()).hasSize(51);
        assertThat(walk.truncations()).containsExactly(new PlanTruncation("breadth", "root", 10_000, 50));
        // The whole plan is 1 read at the wide node + 1 per enumerated child: bounded by the caps,
        // not by the 10k.
        assertThat(reads.get()).isEqualTo(51);
    }

    @Test
    void theNodeCapStopsTheWalkAndEveryFurtherRead() {
        List<Member> first = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            first.add(member("c-" + i, "child"));
            childrenOf("c-" + i, member("g-" + i, "grandchild"));
        }
        children.put("root", new Page(first, 4));

        Walk walk = walk(new Caps(10, 50, 4), member("root", "parent"));

        assertThat(walk.nodes()).hasSize(4);
        assertThat(walk.truncations()).containsExactly(new PlanTruncation("nodes", "root", 4, 3));
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void childrenBelowTheDepthCapAreCountedNotWalked() {
        childrenOf("root", member("a", "hierA"));
        childrenOf("a", member("b", "hierB"));

        Walk walk = walk(new Caps(1, 50, 500), member("root", "hierRoot"));

        assertThat(walk.nodes()).extracting(PlanNode::instanceId).containsExactly("root", "a");
        assertThat(walk.truncations()).containsExactly(new PlanTruncation("depth", "a", 1, 0));
    }

    @Test
    void aDefinitionThatIsAlsoAnAncestorIsReportedAndKeptInThePlan() {
        // hierRoot → hierA → hierRoot (a recursive call): the instance tree is still a tree.
        childrenOf("root", member("a", "hierA"));
        childrenOf("a", member("again", "hierRoot"));

        Walk walk = walk(CAPS, member("root", "hierRoot"));

        assertThat(walk.nodes()).extracting(PlanNode::instanceId).containsExactly("root", "a", "again");
        assertThat(walk.circularReferences())
                .containsExactly(new HierarchyTree.PlanCircularReference("hierRoot", "again", "root", 2));
        assertThat(walk.truncations()).isEmpty();
    }

    @Test
    void anInstanceUnderTwoParentsIsRefusedAsACycleNotOrdered() {
        childrenOf("root", member("a", "hierA"));
        childrenOf("a", member("root", "hierRoot"));

        Walk walk = walk(CAPS, member("root", "hierRoot"));

        assertThat(walk.nodes()).extracting(PlanNode::instanceId).containsExactly("root", "a");
        assertThat(walk.truncations()).containsExactly(new PlanTruncation("instance-cycle", "root", 1, 0));
    }

    @Test
    void aCohortRootThatIsAlsoAnotherRootsDescendantHangsUnderItsRealParent() {
        // A recursive definition: the cohort of hierRoot holds the outer AND the nested instance.
        childrenOf("outer", member("a", "hierA"));
        childrenOf("a", member("nested", "hierRoot"));
        childrenOf("nested", member("leaf", "hierB"));

        Walk walk = walk(CAPS, member("outer", "hierRoot"), member("nested", "hierRoot"));

        assertThat(walk.truncations()).isEmpty();
        assertThat(walk.nodes())
                .extracting(PlanNode::instanceId, PlanNode::parentInstanceId, PlanNode::depth)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("outer", null, 0),
                        org.assertj.core.groups.Tuple.tuple("a", "outer", 1),
                        org.assertj.core.groups.Tuple.tuple("nested", "a", 2),
                        org.assertj.core.groups.Tuple.tuple("leaf", "nested", 3));
        assertThat(walk.circularReferences())
                .containsExactly(new HierarchyTree.PlanCircularReference("hierRoot", "nested", "outer", 2));
    }

    @Test
    void aRootCohortWiderThanItsPageIsATruncation() {
        Walk walk = HierarchyTree.walk(
                List.of(member("r1", "hierRoot"), member("r2", "hierRoot")), 900, id -> new Page(List.of(), 0), CAPS);

        assertThat(walk.nodes()).hasSize(2);
        assertThat(walk.truncations()).containsExactly(new PlanTruncation("roots", null, 900, 2));
    }
}

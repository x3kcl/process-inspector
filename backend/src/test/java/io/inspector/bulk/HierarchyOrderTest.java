package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.action.GuardRefusedException;
import io.inspector.migration.HierarchyPlanService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Rung 1: children first, then parent (#497) — the order and the block rule, no engine. */
class HierarchyOrderTest {

    private static final String ENGINE = "engine-a";
    private static final UUID JOB = UUID.randomUUID();
    private int ordinal;

    private BulkJobItem item(String id, Integer level, String parent) {
        BulkJobItem item = new BulkJobItem(JOB, ordinal++, ENGINE, id, "analysis", BulkJobItem.State.pending);
        if (level != null) {
            item.placeInHierarchy(level, parent);
        }
        return item;
    }

    private static BulkJobItem settled(BulkJobItem item, BulkJobItem.State state, String detail) {
        item.settle(state, detail, null, Instant.EPOCH);
        return item;
    }

    @Test
    void levelsRunDeepestFirstAndAnUnplacedJobIsOneGroup() {
        BulkJobItem root = item("root", 0, null);
        BulkJobItem a = item("a", 1, "root");
        BulkJobItem b1 = item("b1", 2, "a");
        BulkJobItem b2 = item("b2", 2, "a");

        assertThat(HierarchyOrder.levels(List.of(root, a, b1, b2)))
                .containsExactly(List.of(b1, b2), List.of(a), List.of(root));

        BulkJobItem plain1 = item("p1", null, null);
        BulkJobItem plain2 = item("p2", null, null);
        assertThat(HierarchyOrder.levels(List.of(plain1, plain2))).containsExactly(List.of(plain1, plain2));
    }

    @Test
    void aParentGoesOnlyWhenEveryChildReachedItsTarget() {
        BulkJobItem parent = item("a", 1, "root");
        BulkJobItem ok = settled(item("b1", 2, "a"), BulkJobItem.State.ok, "migrated");
        BulkJobItem gone = settled(
                item("b2", 2, "a"), BulkJobItem.State.skipped, "instance-not-running: Instance b2 is not running");

        assertThat(HierarchyOrder.blockedBy(parent, List.of(parent, ok, gone), child -> false))
                .isNull();
    }

    @Test
    void aFailedUnknownOrProtectedChildAlwaysBlocksItsParentAndNamesTheCause() {
        for (BulkJobItem.State state :
                List.of(BulkJobItem.State.failed, BulkJobItem.State.unknown, BulkJobItem.State.skipped_protected)) {
            BulkJobItem parent = item("a", 1, "root");
            BulkJobItem child = settled(item("b", 2, "a"), state, "engine rejected (500): boom");

            // Even when a read says nothing is left of it: these outcomes need the operator.
            String blocked = HierarchyOrder.blockedBy(parent, List.of(parent, child), c -> true);

            assertThat(blocked)
                    .as(state.name())
                    .contains("blocked by child engine-a:b")
                    .contains(state.name())
                    .contains("engine rejected (500): boom");
        }
    }

    @Test
    void aChildThatWasNeverSentBlocksItsParentOnlyWhileItStillHasToBeMigrated() {
        BulkJobItem parent = item("a", 1, "root");
        // The analysis refused it: it had ended, or was already on the target, before the analysis ran.
        BulkJobItem neverSent = settled(
                item("b", 2, "a"),
                BulkJobItem.State.not_run,
                "not sent — the analysis blocked this instance: Instance b is not running");
        // Its own child blocked it.
        BulkJobItem chained =
                settled(item("c", 2, "a"), BulkJobItem.State.not_run, "blocked by child engine-a:d (failed: boom)");

        // Still running on the old version: the block holds and names the child.
        assertThat(HierarchyOrder.blockedBy(parent, List.of(parent, neverSent), child -> false))
                .contains("blocked by child engine-a:b")
                .contains("not_run")
                .contains("Instance b is not running");
        assertThat(HierarchyOrder.blockedBy(parent, List.of(parent, chained), child -> false))
                .contains("blocked by child engine-a:c");
        // A read shows it gone or on its target: nothing is left to migrate below the parent.
        assertThat(HierarchyOrder.blockedBy(parent, List.of(parent, neverSent, chained), child -> true))
                .isNull();
        // The read is per child: one child still on the old version keeps the parent back.
        assertThat(HierarchyOrder.blockedBy(parent, List.of(parent, neverSent, chained), child -> child == neverSent))
                .contains("blocked by child engine-a:c");
    }

    @Test
    void aChildSkippedBecauseItMovedBlocksUnlessItIsOnItsTargetNow() {
        BulkJobItem parent = item("a", 1, "root");
        BulkJobItem moved = settled(
                item("b", 2, "a"), BulkJobItem.State.skipped, "instance-moved-since-preview: token position changed");

        assertThat(HierarchyOrder.blockedBy(parent, List.of(parent, moved), child -> false))
                .contains("blocked by child engine-a:b");
        // Someone else already moved it to the target: nothing is left to migrate below the parent.
        assertThat(HierarchyOrder.blockedBy(parent, List.of(parent, moved), child -> true))
                .isNull();
    }

    @Test
    void aParentNeverBlocksItsChildAndSiblingsAreIndependent() {
        BulkJobItem parent = settled(item("a", 1, "root"), BulkJobItem.State.failed, "boom");
        BulkJobItem child = item("b", 2, "a");
        BulkJobItem sibling = settled(item("c", 2, "a"), BulkJobItem.State.failed, "boom");

        assertThat(HierarchyOrder.blockedBy(child, List.of(parent, child, sibling), c -> false))
                .isNull();
    }

    @Test
    void aContinuationKeepsTheOrderAndRefusesAParentWhoseChildIsUnresolved() {
        BulkJobItem root = settled(item("root", 0, null), BulkJobItem.State.not_run, "blocked");
        BulkJobItem a = settled(item("a", 1, "root"), BulkJobItem.State.not_run, "blocked");
        BulkJobItem b = settled(item("b", 2, "a"), BulkJobItem.State.failed, "boom");
        List<BulkJobItem> prior = List.of(b, a, root);
        BulkDtos.BulkTarget rootTarget = new BulkDtos.BulkTarget(ENGINE, "root", "analysis");
        BulkDtos.BulkTarget aTarget = new BulkDtos.BulkTarget(ENGINE, "a", "analysis");
        BulkDtos.BulkTarget bTarget = new BulkDtos.BulkTarget(ENGINE, "b", "analysis");

        Map<String, HierarchyOrder.Placement> all =
                HierarchyOrder.carryOver(prior, List.of(bTarget, aTarget, rootTarget), child -> false);
        assertThat(all)
                .containsEntry("engine-a:root", new HierarchyOrder.Placement(0, null, null))
                .containsEntry("engine-a:a", new HierarchyOrder.Placement(1, "root", null))
                .containsEntry("engine-a:b", new HierarchyOrder.Placement(2, "a", null));

        // The parent alone, its failed child left behind and not on target: refused.
        assertThatThrownBy(() -> HierarchyOrder.carryOver(prior, List.of(aTarget), child -> false))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-child-unresolved"));
        // Fixed out of band and verified on its target: the parent may be continued alone.
        assertThat(HierarchyOrder.carryOver(prior, List.of(aTarget), child -> true))
                .containsOnlyKeys("engine-a:a");
        // A cohort job's items carry no placement.
        BulkJobItem plain = settled(item("p", null, null), BulkJobItem.State.failed, "boom");
        assertThat(HierarchyOrder.carryOver(
                        List.of(plain), List.of(new BulkDtos.BulkTarget(ENGINE, "p", "analysis")), child -> false))
                .isEmpty();
    }

    @Test
    void aContinuationKeepsAnAnalysisBlockedItemUnsentAndLetsAParentGoOnceNothingIsLeftBelowIt() {
        BulkJobItem a = settled(item("a", 1, null), BulkJobItem.State.not_run, "blocked by child engine-a:b");
        String notSent = HierarchyPlanService.NOT_SENT + ": Instance b is not running";
        BulkJobItem b = settled(item("b", 2, "a"), BulkJobItem.State.not_run, notSent);
        List<BulkJobItem> prior = List.of(b, a);
        BulkDtos.BulkTarget aTarget = new BulkDtos.BulkTarget(ENGINE, "a", "analysis");
        BulkDtos.BulkTarget bTarget = new BulkDtos.BulkTarget(ENGINE, "b", "analysis");

        // The drawer continues every not-run item: b stays listed and unsent, a keeps its place.
        assertThat(HierarchyOrder.carryOver(prior, List.of(bTarget, aTarget), child -> false))
                .containsEntry("engine-a:b", new HierarchyOrder.Placement(2, "a", notSent))
                .containsEntry("engine-a:a", new HierarchyOrder.Placement(1, null, null));
        // The parent alone: refused while b still runs on the old version, allowed once b is gone.
        assertThatThrownBy(() -> HierarchyOrder.carryOver(prior, List.of(aTarget), child -> false))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-child-unresolved"));
        assertThat(HierarchyOrder.carryOver(prior, List.of(aTarget), child -> true))
                .containsOnlyKeys("engine-a:a");
    }
}

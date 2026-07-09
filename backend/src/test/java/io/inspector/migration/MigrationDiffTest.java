package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.migration.ActivityDiffEntry.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung-1 pure test: the BFF static auto-map pre-check classifies each ACTIVE source activity
 * against the target model — auto-mapped, flagged (needs a mapping), or an advisory
 * type/nesting warning — and honors operator overrides. This is the estimate that drives the
 * targeted mapping UI; the engine is the ground truth only at execute (P0 re-lock).
 */
class MigrationDiffTest {

    /** A trivial in-memory model: id → (type, nestingPath). */
    private static MigrationDiff.ModelView model(Map<String, ModelNode> nodes) {
        return new MigrationDiff.ModelView() {
            @Override
            public boolean has(String id) {
                return nodes.containsKey(id);
            }

            @Override
            public String typeOf(String id) {
                ModelNode n = nodes.get(id);
                return n == null ? null : n.type();
            }

            @Override
            public String nameOf(String id) {
                ModelNode n = nodes.get(id);
                return n == null ? null : n.name();
            }

            @Override
            public List<String> nestingPath(String id) {
                ModelNode n = nodes.get(id);
                return n == null ? List.of() : n.nesting();
            }
        };
    }

    private record ModelNode(String type, String name, List<String> nesting) {
        static ModelNode of(String type) {
            return new ModelNode(type, type, List.of());
        }

        static ModelNode nested(String type, String... path) {
            return new ModelNode(type, type, List.of(path));
        }
    }

    @Test
    void renamedActivityWithNoTargetIsFlaggedUnmapped() {
        var source = model(Map.of("reviewTask", ModelNode.of("userTask")));
        var target = model(Map.of("approveTask", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("reviewTask"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.FLAGGED_UNMAPPED);
            assertThat(e.toActivityId()).isNull();
            assertThat(e.isBlocker()).isTrue();
        });
    }

    @Test
    void operatorOverrideMapsTheFlaggedActivity() {
        var source = model(Map.of("reviewTask", ModelNode.of("userTask")));
        var target = model(Map.of("approveTask", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(
                source, target, List.of("reviewTask"), List.of(MigrationMapping.oneToOne("reviewTask", "approveTask")));

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.MAPPED_BY_OVERRIDE);
            assertThat(e.toActivityId()).isEqualTo("approveTask");
            assertThat(e.toType()).isEqualTo("userTask");
            assertThat(e.isBlocker()).isFalse();
        });
    }

    @Test
    void sameIdSameTypeSameNestingAutoMaps() {
        var source = model(Map.of("reviewTask", ModelNode.of("userTask")));
        var target = model(Map.of("reviewTask", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("reviewTask"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.AUTO_MAPPED);
            assertThat(e.toActivityId()).isEqualTo("reviewTask");
            assertThat(e.isBlocker()).isFalse();
            assertThat(e.isWarning()).isFalse();
        });
    }

    @Test
    void sameIdDifferentTypeIsTheLoudSilentCorruptionWarning() {
        var source = model(Map.of("step2", ModelNode.of("userTask")));
        var target = model(Map.of("step2", ModelNode.of("serviceTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("step2"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.TYPE_CHANGED);
            assertThat(e.isWarning()).isTrue();
            assertThat(e.isBlocker()).isFalse();
            assertThat(e.detail()).contains("userTask").contains("serviceTask");
        });
    }

    @Test
    void sameIdSameTypeDifferentNestingWarns() {
        var source = model(Map.of("task", ModelNode.of("userTask")));
        var target = model(Map.of("task", ModelNode.nested("userTask", "subProc")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("task"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.NESTING_CHANGED);
            assertThat(e.isWarning()).isTrue();
            assertThat(e.isBlocker()).isFalse();
        });
    }

    @Test
    void onlyActiveActivitiesAreClassified_inactiveRenamesAreIgnored() {
        // 'oldStep' was renamed in the target but the token is NOT on it — the engine only needs
        // mappings for token-holding activities, so it must not be flagged.
        var source = model(Map.of("active", ModelNode.of("userTask"), "oldStep", ModelNode.of("userTask")));
        var target = model(Map.of("active", ModelNode.of("userTask"), "renamedStep", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("active"), List.of());

        assertThat(diff).singleElement().satisfies(e -> assertThat(e.status()).isEqualTo(Status.AUTO_MAPPED));
    }

    @Test
    void entriesAreSortedByActivityIdForDeterminism() {
        var source = model(
                Map.of("b", ModelNode.of("userTask"), "a", ModelNode.of("userTask"), "c", ModelNode.of("userTask")));
        var target = source;

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("c", "a", "b"), List.of());

        assertThat(diff).extracting(ActivityDiffEntry::fromActivityId).containsExactly("a", "b", "c");
    }
}

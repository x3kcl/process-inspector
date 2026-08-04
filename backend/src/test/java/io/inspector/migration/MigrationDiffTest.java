package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.migration.ActivityDiffEntry.Status;
import io.inspector.migration.MigrationFinding.Code;
import io.inspector.migration.MigrationFinding.Severity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Rung-1 pure test: the BFF static auto-map pre-check classifies each ACTIVE source activity
 * against the target model — auto-mapped, flagged (needs a mapping), or an advisory
 * type/nesting warning — honors operator overrides, and annotates every classification with the
 * typed findings taxonomy locked by INSTANCE-MIGRATION.md §14. This is the estimate that drives
 * the targeted mapping UI; the engine is the ground truth only at execute (P0 re-lock).
 *
 * <p>Rung 4 ({@code MigrationFindingsIT}) proves the two blocker→warning downgrades against the
 * REAL engines; this class pins the classification logic itself.
 */
class MigrationDiffTest {

    /** A trivial in-memory model: id → (type, nestingPath, optional multi-instance root). */
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

            @Override
            public Optional<String> multiInstanceScopeOf(String id) {
                ModelNode n = nodes.get(id);
                return Optional.ofNullable(n == null ? null : n.miRoot());
            }
        };
    }

    private record ModelNode(String type, String name, List<String> nesting, String miRoot) {
        static ModelNode of(String type) {
            return new ModelNode(type, type, List.of(), null);
        }

        static ModelNode nested(String type, String... path) {
            return new ModelNode(type, type, List.of(path), null);
        }

        /** A multi-instance ROOT (it is its own MI scope — {@code multiInstanceScopeOf(id) == id}). */
        static ModelNode miRoot(String id) {
            return new ModelNode("subProcess", id, List.of(), id);
        }
    }

    private static List<Code> codes(ActivityDiffEntry entry) {
        return entry.findings().stream().map(MigrationFinding::code).toList();
    }

    private static ActivityDiffEntry entry(List<ActivityDiffEntry> diff, String id) {
        return diff.stream()
                .filter(e -> e.fromActivityId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + id + " in " + diff));
    }

    /* ------------------------------- the pre-existing ladder ------------------------------- */

    @Test
    void renamedActivityWithNoTargetIsFlaggedUnmapped() {
        var source = model(Map.of("reviewTask", ModelNode.of("userTask")));
        var target = model(Map.of("approveTask", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("reviewTask"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.FLAGGED_UNMAPPED);
            assertThat(e.toActivityId()).isNull();
            assertThat(e.isBlocker()).isTrue();
            assertThat(codes(e)).containsExactly(Code.UNMAPPED_ACTIVE_ACTIVITY);
            assertThat(e.findings().get(0).severity()).isEqualTo(Severity.BLOCKER_ADVICE);
            // Panel-demanded wording: never readable as an engine verdict (§14.3).
            assertThat(e.findings().get(0).detail())
                    .contains("cannot build a migration instruction")
                    .contains("nothing to send");
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
            assertThat(e.findings()).isEmpty();
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
            assertThat(e.findings()).isEmpty();
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
            assertThat(codes(e)).containsExactly(Code.TYPE_CHANGED_SAME_ID);
            // [M6] calibration: the new behavior can run DURING the migrate call, not later.
            assertThat(e.findings().get(0).detail()).contains("execute IMMEDIATELY as part of the migrate call");
        });
    }

    @Test
    void sameIdSameTypeDifferentNestingWarns() {
        var source = model(Map.of("task", ModelNode.of("userTask"), "subProc", ModelNode.of("subProcess")));
        var target =
                model(Map.of("task", ModelNode.nested("userTask", "subProc"), "subProc", ModelNode.of("subProcess")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("task"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.NESTING_CHANGED);
            assertThat(e.isWarning()).isTrue();
            assertThat(e.isBlocker()).isFalse();
            assertThat(codes(e)).containsExactly(Code.NESTING_PATH_CHANGED);
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

    /* --------------------- §14 downgrade 1: a removed ACTIVE SCOPE warns --------------------- */

    @Test
    void removedActiveScopeIsAWarningNotABlocker_theCalibratedFalseBlockerIsGone() {
        // taxProbeA v1→v2 shape ([M3]): scopeA disappears; the engine accepted this with an EMPTY
        // activityMappings list on BOTH 6.8.0 and 7.1.0. Blocking it made a legitimate recovery
        // impossible through the BFF (422 before any engine contact).
        var source = model(Map.of(
                "scopeA", ModelNode.of("subProcess"),
                "stepA", ModelNode.nested("userTask", "scopeA")));
        var target = model(Map.of("stepA", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("scopeA", "stepA"), List.of());

        ActivityDiffEntry scope = entry(diff, "scopeA");
        assertThat(scope.status()).isEqualTo(Status.SCOPE_REMOVED);
        assertThat(scope.isBlocker()).isFalse();
        assertThat(scope.isWarning()).isTrue();
        assertThat(codes(scope)).containsExactly(Code.ACTIVE_SCOPE_REMOVED);
        assertThat(scope.findings().get(0).detail())
                .contains("Flowable 6.8 and 7.1")
                .contains("cannot know what state is lost")
                // §14.11: the pre-#355-fix copy said "its tokens re-home outward" (plural). Only
                // ONE ever does — the corrected wording must state the measured behavior.
                .contains("exactly ONE token surviving a scope collapse")
                .doesNotContain("its tokens re-home outward");

        // and the whole diff is now executable — nothing is unsendable.
        assertThat(diff).noneMatch(ActivityDiffEntry::isBlocker);
    }

    /* ------------- §14.11: the LOSSY sub-case of the same collapse is a BLOCKER ------------- */

    @Test
    void aScopeCollapseWithTwoConcurrentTokensInsideIsABlocker_notAWarning() {
        // The #355 regression shape: a parallel fork INSIDE the dissolving scope. Measured [M10]:
        // the engine returns 200, keeps exactly one token and ends the rest with no error, no
        // delete reason and no job — so decision 10's atomic-rejection backstop cannot fire.
        var source = model(Map.of(
                "scopeP", ModelNode.of("subProcess"),
                "stepP1", ModelNode.nested("userTask", "scopeP"),
                "stepP2", ModelNode.nested("userTask", "scopeP")));
        var target = model(Map.of(
                "stepP1", ModelNode.of("userTask"),
                "stepP2", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff =
                MigrationDiff.diff(source, target, List.of("scopeP", "stepP1", "stepP2"), List.of());

        ActivityDiffEntry scope = entry(diff, "scopeP");
        assertThat(scope.status()).isEqualTo(Status.SCOPE_REMOVED);
        assertThat(scope.isBlocker()).isTrue();
        assertThat(codes(scope)).containsExactly(Code.SCOPE_COLLAPSE_TOKEN_LOSS);
        assertThat(scope.findings().get(0).severity()).isEqualTo(Severity.BLOCKER_ADVICE);
        assertThat(scope.findings().get(0).detail())
                .contains("2 live tokens are inside it")
                .contains("the engine will keep 1")
                .contains("Supplying a target mapping does not help");
    }

    @Test
    void tokenMultiplicityIsReadFromTheMULTISET_twoTokensOnOneActivityIdStillBlock() {
        // The trap #355 shipped: MigrationDiff classifies per DISTINCT id, so two tokens parked on
        // the SAME id inside the scope are ONE diff row. Counting rows would see "1 token" and
        // downgrade to a warning; the count must come from the active-execution multiset.
        var source = model(Map.of(
                "scopeP", ModelNode.of("subProcess"),
                "stepP", ModelNode.nested("userTask", "scopeP")));
        var target = model(Map.of("stepP", ModelNode.of("userTask")));

        List<ActivityDiffEntry> oneToken = MigrationDiff.diff(source, target, List.of("scopeP", "stepP"), List.of());
        assertThat(entry(oneToken, "scopeP").isBlocker()).isFalse();

        // Same DISTINCT id set, two live executions on stepP (a multi-instance-free parallel fork
        // that re-converges on one activity).
        List<ActivityDiffEntry> twoTokens =
                MigrationDiff.diff(source, target, List.of("scopeP", "stepP", "stepP"), List.of());
        assertThat(entry(twoTokens, "scopeP").isBlocker()).isTrue();
        assertThat(codes(entry(twoTokens, "scopeP"))).containsExactly(Code.SCOPE_COLLAPSE_TOKEN_LOSS);
    }

    @Test
    void aScopeRENAMEWithConcurrentTokensIsTheSameLossyCollapse() {
        // ids otherwise identical, only the scope id changed — indistinguishable to the engine
        // from a removal, and equally lossy.
        var source = model(Map.of(
                "scopeA", ModelNode.of("subProcess"),
                "stepP1", ModelNode.nested("userTask", "scopeA"),
                "stepP2", ModelNode.nested("userTask", "scopeA")));
        var target = model(Map.of(
                "scopeB", ModelNode.of("subProcess"),
                "stepP1", ModelNode.nested("userTask", "scopeB"),
                "stepP2", ModelNode.nested("userTask", "scopeB")));

        List<ActivityDiffEntry> diff =
                MigrationDiff.diff(source, target, List.of("scopeA", "stepP1", "stepP2"), List.of());

        assertThat(codes(entry(diff, "scopeA"))).containsExactly(Code.SCOPE_COLLAPSE_TOKEN_LOSS);
        assertThat(diff).anyMatch(ActivityDiffEntry::isBlocker);
    }

    @Test
    void threeConcurrentTokensReportTheirRealCount() {
        var source = model(Map.of(
                "scopeP", ModelNode.of("subProcess"),
                "s1", ModelNode.nested("userTask", "scopeP"),
                "s2", ModelNode.nested("userTask", "scopeP"),
                "s3", ModelNode.nested("userTask", "scopeP")));
        var target = model(
                Map.of("s1", ModelNode.of("userTask"), "s2", ModelNode.of("userTask"), "s3", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff =
                MigrationDiff.diff(source, target, List.of("scopeP", "s1", "s2", "s3"), List.of());

        assertThat(entry(diff, "scopeP").findings().get(0).detail()).contains("3 live tokens are inside it");
    }

    @Test
    void nestedScopeExecutionsAreNotTokens_twoLevelNestingWithOneTokenStaysAWarning() {
        // Both scopes dissolve. The active list holds scopeOuter, scopeInner AND stepX ([M2]) — but
        // only stepX is a TOKEN. Counting scope executions would over-block a shape the reviewer
        // verified is safe and continuable.
        var source = model(Map.of(
                "scopeOuter", ModelNode.of("subProcess"),
                "scopeInner", ModelNode.nested("subProcess", "scopeOuter"),
                "stepX", ModelNode.nested("userTask", "scopeOuter", "scopeInner")));
        var target = model(Map.of("stepX", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff =
                MigrationDiff.diff(source, target, List.of("scopeOuter", "scopeInner", "stepX"), List.of());

        assertThat(diff).noneMatch(ActivityDiffEntry::isBlocker);
        assertThat(codes(entry(diff, "scopeOuter"))).containsExactly(Code.ACTIVE_SCOPE_REMOVED);
        assertThat(codes(entry(diff, "scopeInner"))).containsExactly(Code.ACTIVE_SCOPE_REMOVED);
    }

    @Test
    void aBoundarySubscriptionInsideTheScopeIsNotASecondToken() {
        // bndX is a child execution of stepX's ([M2]) — one token, not two.
        var source = model(Map.of(
                "scopeP", ModelNode.of("subProcess"),
                "stepX", ModelNode.nested("userTask", "scopeP"),
                "bndX", ModelNode.nested("boundaryEvent", "scopeP")));
        var target = model(Map.of("stepX", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff =
                MigrationDiff.diff(source, target, List.of("scopeP", "stepX", "bndX"), List.of());

        assertThat(codes(entry(diff, "scopeP"))).containsExactly(Code.ACTIVE_SCOPE_REMOVED);
        assertThat(entry(diff, "scopeP").isBlocker()).isFalse();
    }

    @Test
    void aScopeNestedInsideAMultiInstanceBodyKeepsTheBlocker_notJustTheMiRootItself() {
        // Preflight honesty: a plain subprocess inside an MI body previewed green and then 500'd at
        // execute ("…or its MI Parent", [M7]). The MI guard now covers any MI DESCENDANT scope.
        var source = model(Map.of(
                "plainScope",
                new ModelNode("subProcess", "plainScope", List.of("miRoot"), "miRoot"),
                "miRoot",
                ModelNode.miRoot("miRoot")));
        var target = model(Map.of("other", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("plainScope"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.FLAGGED_UNMAPPED);
            assertThat(codes(e)).containsExactly(Code.UNMAPPED_ACTIVE_ACTIVITY);
        });
    }

    @Test
    void aTokenInsideARemovedScopeGetsTheMoreSpecificFinding() {
        var source = model(Map.of(
                "scopeA", ModelNode.of("subProcess"),
                "stepA", ModelNode.nested("userTask", "scopeA")));
        var target = model(Map.of("stepA", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("scopeA", "stepA"), List.of());

        ActivityDiffEntry leaf = entry(diff, "stepA");
        assertThat(leaf.status()).isEqualTo(Status.NESTING_CHANGED);
        assertThat(leaf.isBlocker()).isFalse();
        // ACTIVE_IN_REMOVED_SCOPE takes precedence over NESTING_PATH_CHANGED (§14.3).
        assertThat(codes(leaf)).containsExactly(Code.ACTIVE_IN_REMOVED_SCOPE);
        assertThat(leaf.findings().get(0).detail()).contains("scopeA");
    }

    @Test
    void aMultiInstanceRootWithNoTargetKEEPSTheBlocker_noUncalibratedDowngrade() {
        // §14.3 MI-root retention: P14-B requires calibration evidence for a downgrade and no MI
        // case was calibrated — downgrading would be the rule-guessing the ceiling forbids.
        var source = model(Map.of("miScope", ModelNode.miRoot("miScope")));
        var target = model(Map.of("other", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("miScope"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.FLAGGED_UNMAPPED);
            assertThat(e.isBlocker()).isTrue();
            assertThat(codes(e)).containsExactly(Code.UNMAPPED_ACTIVE_ACTIVITY);
        });
    }

    @Test
    void aScopeThatSurvivesIsNotFlaggedAtAll() {
        var source = model(Map.of("scopeA", ModelNode.of("subProcess")));
        var target = model(Map.of("scopeA", ModelNode.of("subProcess")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("scopeA"), List.of());

        assertThat(diff).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(Status.AUTO_MAPPED);
            assertThat(e.findings()).isEmpty();
        });
    }

    /* ------------------- §14 downgrade 2: a removed BOUNDARY EVENT warns ------------------- */

    @Test
    void removedBoundaryEventIsAWarningNotABlocker_theCalibratedFalseBlockerIsGone() {
        // taxProbeC v1→v2 shape ([M4]): the engine returned 200 with empty mappings on 6.8.0 AND
        // 7.1.0 and silently dropped the subscription + its timer job.
        var source = model(Map.of(
                "stepC", ModelNode.of("userTask"),
                "bndC", ModelNode.of("boundaryEvent")));
        var target = model(Map.of("stepC", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("stepC", "bndC"), List.of());

        ActivityDiffEntry boundary = entry(diff, "bndC");
        assertThat(boundary.status()).isEqualTo(Status.BOUNDARY_REMOVED);
        assertThat(boundary.isBlocker()).isFalse();
        assertThat(boundary.isWarning()).isTrue();
        assertThat(codes(boundary)).containsExactly(Code.BOUNDARY_SUBSCRIPTION_REMOVED);
        assertThat(boundary.findings().get(0).detail()).contains("disappears at migrate");

        assertThat(entry(diff, "stepC").status()).isEqualTo(Status.AUTO_MAPPED);
        assertThat(diff).noneMatch(ActivityDiffEntry::isBlocker);
    }

    /* ---------------------------- instance-level: the clock INFO ---------------------------- */

    @Test
    void anyLiveBoundarySubscriptionRaisesTheClockResetInfo_changedOrNot() {
        // [M5] is change-INDEPENDENT: an identical PT72H timer reset on 6.8 and was preserved on
        // 7.1, so the info is raised whenever ANY boundary execution is live.
        var source = model(Map.of(
                "stepC", ModelNode.of("userTask"),
                "bndC", ModelNode.of("boundaryEvent")));
        var target = source;

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, target, List.of("stepC", "bndC"), List.of());
        assertThat(diff).allMatch(e -> e.findings().isEmpty());

        List<MigrationFinding> instanceFindings = MigrationDiff.instanceFindings(diff);
        assertThat(instanceFindings).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo(Code.BOUNDARY_CLOCK_RESET);
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.activityId()).isNull();
            // "may", never "will" — the behavior is version-divergent and unknowable here.
            assertThat(f.detail()).contains("MAY restart").contains("cannot know which behavior");
        });
    }

    @Test
    void noBoundaryExecutionMeansNoInstanceFinding() {
        var source = model(Map.of("stepC", ModelNode.of("userTask")));

        List<ActivityDiffEntry> diff = MigrationDiff.diff(source, source, List.of("stepC"), List.of());

        assertThat(MigrationDiff.instanceFindings(diff)).isEmpty();
    }

    /* ------------------------------- the taxonomy is CLOSED ------------------------------- */

    @Test
    void theVocabularyIsExactlyTheEightCodesLockedByTheDesign() {
        // §14.3/§14.11 lock eight proven-computable codes; §14.5 lists eight candidates DROPPED
        // under the ceiling. That list is not a backlog — adding a code here without a design
        // change and a taxonomyVersion bump is a rails violation, so pin the vocabulary.
        assertThat(Code.values())
                .containsExactlyInAnyOrder(
                        Code.UNMAPPED_ACTIVE_ACTIVITY,
                        Code.ACTIVE_SCOPE_REMOVED,
                        Code.SCOPE_COLLAPSE_TOKEN_LOSS,
                        Code.ACTIVE_IN_REMOVED_SCOPE,
                        Code.NESTING_PATH_CHANGED,
                        Code.TYPE_CHANGED_SAME_ID,
                        Code.BOUNDARY_SUBSCRIPTION_REMOVED,
                        Code.BOUNDARY_CLOCK_RESET);
        // §14.8: a severity reassignment or a code addition BOTH bump the taxonomy version.
        assertThat(MigrationFinding.TAXONOMY_VERSION).isEqualTo(2);
    }

    @Test
    void theOnlyTwoBlockerCodesAreTheDocumentedRefusals_everythingElseStaysAdvisory() {
        // §14.0 P14-B/§14.11: exactly two refusal grounds — nothing sendable, and the one MEASURED
        // destruction the engine reports as success. Every other finding is advisory.
        assertThat(java.util.Arrays.stream(Code.values())
                        .filter(c -> c == Code.UNMAPPED_ACTIVE_ACTIVITY || c == Code.SCOPE_COLLAPSE_TOKEN_LOSS))
                .hasSize(2);

        var source = model(Map.of(
                "gone", ModelNode.of("userTask"),
                "scopeA", ModelNode.of("subProcess"),
                "bndC", ModelNode.of("boundaryEvent"),
                "typed", ModelNode.of("userTask"),
                "moved", ModelNode.nested("userTask", "scopeA")));
        var target = model(Map.of("typed", ModelNode.of("serviceTask"), "moved", ModelNode.of("userTask")));

        // One token inside scopeA — the calibrated, safe collapse. Still exactly one blocker.
        List<ActivityDiffEntry> diff =
                MigrationDiff.diff(source, target, List.of("gone", "scopeA", "bndC", "typed", "moved"), List.of());

        assertThat(diff.stream()
                        .flatMap(e -> e.findings().stream())
                        .filter(MigrationFinding::isBlockerAdvice)
                        .map(MigrationFinding::code))
                .containsExactly(Code.UNMAPPED_ACTIVE_ACTIVITY);
    }
}

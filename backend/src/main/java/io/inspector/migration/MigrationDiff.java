package io.inspector.migration;

import io.inspector.surgery.BpmnStructure;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The BFF static auto-map pre-check (P0 re-lock decision P0-1). Given the source and target
 * process models and the instance's currently-ACTIVE activity ids, it classifies each active
 * activity into the execution-document machinery ({@link ActivityDiffEntry.Status}) and annotates
 * it with typed {@link MigrationFinding}s (INSTANCE-MIGRATION.md §14). This is an <em>Inspector
 * estimate</em>, never an engine validation: Flowable's REST API exposes no migration validator
 * (P0 spike), so the authoritative check happens only at execute. The diff stays deliberately
 * shallow — id, type, nesting, source node kind — and never reimplements the engine's migration
 * rules (§14.0 P14-A/C: zero new engine calls, and when in doubt label rather than check).
 */
public final class MigrationDiff {

    /**
     * Container element types whose children live in a NESTED scope. Mirrors
     * {@code BpmnStructure}'s own scope set — an active execution on one of these ids is a SCOPE
     * execution, not a leaf token ([M2]).
     */
    static final Set<String> SCOPE_TYPES = Set.of("subProcess", "transaction", "adHocSubProcess");

    static final String BOUNDARY_EVENT_TYPE = "boundaryEvent";

    /** The minimal model contract the diff needs — primitives only, so it is trivially testable. */
    public interface ModelView {
        boolean has(String activityId);

        String typeOf(String activityId);

        String nameOf(String activityId);

        List<String> nestingPath(String activityId);

        /**
         * The multi-instance root whose body contains {@code activityId} (a root maps to itself).
         * Free from the {@code /model} JSON the structure already parsed — no extra engine call.
         * Defaulted so simple fixture models need not implement it.
         */
        default Optional<String> multiInstanceScopeOf(String activityId) {
            return Optional.empty();
        }
    }

    private MigrationDiff() {}

    /** Adapt a parsed {@link BpmnStructure} to the diff's model contract. */
    public static ModelView of(BpmnStructure structure) {
        return new ModelView() {
            @Override
            public boolean has(String activityId) {
                return structure.node(activityId).isPresent();
            }

            @Override
            public String typeOf(String activityId) {
                return structure
                        .node(activityId)
                        .map(BpmnStructure.FlowNode::type)
                        .orElse(null);
            }

            @Override
            public String nameOf(String activityId) {
                return structure
                        .node(activityId)
                        .map(BpmnStructure.FlowNode::name)
                        .orElse(null);
            }

            @Override
            public List<String> nestingPath(String activityId) {
                return structure.nestingPath(activityId);
            }

            @Override
            public Optional<String> multiInstanceScopeOf(String activityId) {
                return structure.multiInstanceScopeOf(activityId);
            }
        };
    }

    /**
     * Classify every active source activity against the target model, honoring operator
     * override mappings. Active ids are processed in sorted order for a deterministic result
     * (stable audit digest, stable UI).
     *
     * <p>Ladder order (first match wins), keyed by the SOURCE model's node kind (§14.3):
     * operator override → scope container → boundary event → leaf-unmapped → type change →
     * nesting change → clean auto-map. Type change stays ahead of the nesting findings: it is
     * the loudest class (decision P0-5) and a same-id-changed-type activity is the one silent
     * corruption path the operator must see first.
     *
     * <p>⚠ {@code activeActivityIds} is a <b>MULTISET</b> — one entry per active execution, so a
     * fork with two tokens on the same activity id appears twice ({@code
     * MigrationService.activeActivityIds()}). Classification is per DISTINCT id (the
     * {@link TreeSet} below), but the scope-collapse token count in §14.11 needs the
     * multiplicity, so the raw collection is threaded down to {@link #classify} rather than the
     * de-duplicated view. Collapsing it before that count would make a two-tokens-on-one-id fork
     * indistinguishable from a single token — the exact blindness that shipped the #355 defect.
     */
    public static List<ActivityDiffEntry> diff(
            ModelView source,
            ModelView target,
            Collection<String> activeActivityIds,
            List<MigrationMapping> overrides) {
        List<String> activeMultiset = List.copyOf(activeActivityIds);
        List<ActivityDiffEntry> entries = new ArrayList<>();
        for (String activeId : new TreeSet<>(activeMultiset)) {
            entries.add(classify(source, target, activeId, overrides, activeMultiset));
        }
        return entries;
    }

    /**
     * Instance-level findings — the ones that are a property of the migration as a whole rather
     * than of one classified activity (§14.8). Today exactly one: {@code BOUNDARY_CLOCK_RESET},
     * raised whenever ANY boundary-event execution is live, changed or not, because
     * re-subscription (and therefore a possible clock restart) happens regardless of model
     * change ([M5]).
     */
    public static List<MigrationFinding> instanceFindings(List<ActivityDiffEntry> entries) {
        boolean anyBoundaryActive = entries.stream().anyMatch(e -> BOUNDARY_EVENT_TYPE.equals(e.fromType()));
        return anyBoundaryActive ? List.of(MigrationFinding.boundaryClockReset()) : List.of();
    }

    private static ActivityDiffEntry classify(
            ModelView source,
            ModelView target,
            String activeId,
            List<MigrationMapping> overrides,
            List<String> activeMultiset) {
        String fromType = source.typeOf(activeId);
        String fromName = source.nameOf(activeId);

        Optional<MigrationMapping> override = overrides.stream()
                .filter(m -> m.coveredFromIds().contains(activeId))
                .findFirst();
        if (override.isPresent()) {
            String to = primaryTarget(override.get());
            String toType = target.has(to) ? target.typeOf(to) : null;
            return new ActivityDiffEntry(
                    activeId,
                    fromType,
                    fromName,
                    ActivityDiffEntry.Status.MAPPED_BY_OVERRIDE,
                    to,
                    toType,
                    "Mapped by the operator to '" + to + "'.",
                    List.of());
        }

        boolean missingFromTarget = !target.has(activeId);

        // --- scope-container ladder: the token lives in a REGION, not on a leaf ([M2]). ---
        if (SCOPE_TYPES.contains(fromType) && missingFromTarget) {
            // Deliberate NON-downgrade (§14.3 "MI-root retention"): P14-B requires calibration
            // evidence for any downgrade and no multi-instance case was calibrated. Downgrading
            // here would be an unproven prediction that the engine tolerates it — exactly the
            // rule-guessing the ceiling forbids. Widened by #355's follow-up to ANY scope with a
            // multi-instance ANCESTOR, not just an MI root: a plain subprocess nested inside an MI
            // body previewed green and then 500'd at execute ("…or its MI Parent", [M7]) — the
            // backstop working correctly, but a dishonest preflight.
            if (source.multiInstanceScopeOf(activeId).isPresent()) {
                return flagged(activeId, fromType, fromName);
            }
            // §14.11 — the ONE lossy sub-case. Read from the active-execution MULTISET, because a
            // parallel fork can hold two tokens on ONE activity id and the classified-entry view
            // (one row per distinct id) cannot see that.
            long liveTokens = liveTokensInside(source, activeMultiset, activeId);
            if (liveTokens > 1) {
                return new ActivityDiffEntry(
                        activeId,
                        fromType,
                        fromName,
                        ActivityDiffEntry.Status.SCOPE_REMOVED,
                        null,
                        null,
                        "The subprocess scope '" + activeId + "' is gone from the target version and " + liveTokens
                                + " live tokens are inside it. The engine keeps exactly ONE and ends the rest"
                                + " silently while returning success — refused before any engine contact.",
                        List.of(MigrationFinding.scopeCollapseTokenLoss(activeId, liveTokens)));
            }
            return new ActivityDiffEntry(
                    activeId,
                    fromType,
                    fromName,
                    ActivityDiffEntry.Status.SCOPE_REMOVED,
                    null,
                    null,
                    "The subprocess scope '" + activeId + "' is gone from the target version; its single live token"
                            + " re-homes outward. No mapping is sent for the scope itself.",
                    List.of(MigrationFinding.activeScopeRemoved(activeId, liveTokens)));
        }

        // --- boundary-event ladder: a subscription, not a token position ([M2]/[M4]). ---
        if (BOUNDARY_EVENT_TYPE.equals(fromType) && missingFromTarget) {
            return new ActivityDiffEntry(
                    activeId,
                    fromType,
                    fromName,
                    ActivityDiffEntry.Status.BOUNDARY_REMOVED,
                    null,
                    null,
                    "The boundary event '" + activeId + "' is gone from the target version; its subscription is"
                            + " dropped at migrate. No mapping is sent for it.",
                    List.of(MigrationFinding.boundarySubscriptionRemoved(activeId)));
        }

        // --- leaf ladder ---
        if (missingFromTarget) {
            return flagged(activeId, fromType, fromName);
        }

        String targetType = target.typeOf(activeId);
        if (!Objects.equals(fromType, targetType)) {
            return new ActivityDiffEntry(
                    activeId,
                    fromType,
                    fromName,
                    ActivityDiffEntry.Status.TYPE_CHANGED,
                    activeId,
                    targetType,
                    "Keeps its id but changed type (" + fromType + " → " + targetType + "). Auto-map accepts"
                            + " this and the engine will likely return success, but the token lands on different"
                            + " behavior — verify this is intended. Neither the Inspector nor Flowable flags it"
                            + " as an error.",
                    List.of(MigrationFinding.typeChangedSameId(activeId, fromType, targetType)));
        }

        List<String> sourcePath = source.nestingPath(activeId);
        List<String> targetPath = target.nestingPath(activeId);
        if (!sourcePath.equals(targetPath)) {
            // ACTIVE_IN_REMOVED_SCOPE takes precedence over NESTING_PATH_CHANGED (§14.3): a scope
            // that DISSOLVED is a materially different (and worse) event than a move between two
            // scopes that both survive.
            Optional<String> removedScope =
                    sourcePath.stream().filter(scope -> !target.has(scope)).findFirst();
            MigrationFinding finding = removedScope
                    .map(scope -> MigrationFinding.activeInRemovedScope(activeId, scope))
                    .orElseGet(() -> MigrationFinding.nestingPathChanged(activeId, sourcePath, targetPath));
            return new ActivityDiffEntry(
                    activeId,
                    fromType,
                    fromName,
                    ActivityDiffEntry.Status.NESTING_CHANGED,
                    activeId,
                    targetType,
                    "Keeps its id and type but moved between subprocess scopes (" + sourcePath + " → " + targetPath
                            + "). Auto-map accepts it; variable scoping and event context may differ.",
                    List.of(finding));
        }

        return new ActivityDiffEntry(
                activeId,
                fromType,
                fromName,
                ActivityDiffEntry.Status.AUTO_MAPPED,
                activeId,
                targetType,
                "Maps by name — same id and type in both versions.",
                List.of());
    }

    /**
     * How many live TOKENS sit inside scope {@code scopeId} (§14.11's predicate). Counted over the
     * active-execution multiset, so N tokens on one activity id count N times.
     *
     * <p>Only genuine token positions count. Scope executions ([M2]) and boundary-event
     * subscriptions are active executions too, but they are not tokens: counting them would flag a
     * two-level nesting or a single task with a boundary timer as "lossy" — both of which live
     * calibration proved safe and continuable. Zero new engine calls: {@code nestingPath} comes
     * from the already-parsed source model (P14-A).
     */
    private static long liveTokensInside(ModelView source, List<String> activeMultiset, String scopeId) {
        return activeMultiset.stream()
                .filter(id -> isTokenPosition(source.typeOf(id)))
                .filter(id -> source.nestingPath(id).contains(scopeId))
                .count();
    }

    private static boolean isTokenPosition(String type) {
        return !SCOPE_TYPES.contains(type) && !BOUNDARY_EVENT_TYPE.equals(type);
    }

    private static ActivityDiffEntry flagged(String activeId, String fromType, String fromName) {
        return new ActivityDiffEntry(
                activeId,
                fromType,
                fromName,
                ActivityDiffEntry.Status.FLAGGED_UNMAPPED,
                null,
                null,
                "No activity with id '" + activeId + "' exists in the target version — the engine cannot"
                        + " auto-map it. Pick a target activity for it.",
                List.of(MigrationFinding.unmappedActiveActivity(activeId)));
    }

    private static String primaryTarget(MigrationMapping mapping) {
        return mapping.toActivityId() != null && !mapping.toActivityId().isBlank()
                ? mapping.toActivityId()
                : mapping.toActivityIds().get(0);
    }
}

package io.inspector.migration;

import io.inspector.surgery.BpmnStructure;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The BFF static auto-map pre-check (P0 re-lock decision P0-1). Given the source and target
 * process models and the instance's currently-ACTIVE activity ids, it classifies each active
 * activity as auto-mappable, unmapped (needs an operator mapping), or an advisory warning
 * (type/nesting changed). This is an <em>Inspector estimate</em>, never an engine validation:
 * Flowable's REST API exposes no migration validator (P0 spike), so the authoritative check
 * happens only at execute. The diff stays deliberately shallow — id, type, nesting — and never
 * reimplements the engine's migration rules.
 */
public final class MigrationDiff {

    /** The minimal model contract the diff needs — primitives only, so it is trivially testable. */
    public interface ModelView {
        boolean has(String activityId);

        String typeOf(String activityId);

        String nameOf(String activityId);

        List<String> nestingPath(String activityId);
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
        };
    }

    /**
     * Classify every active source activity against the target model, honoring operator
     * override mappings. Active ids are processed in sorted order for a deterministic result
     * (stable audit digest, stable UI).
     */
    public static List<ActivityDiffEntry> diff(
            ModelView source,
            ModelView target,
            Collection<String> activeActivityIds,
            List<MigrationMapping> overrides) {
        List<ActivityDiffEntry> entries = new ArrayList<>();
        for (String activeId : new TreeSet<>(activeActivityIds)) {
            String fromType = source.typeOf(activeId);
            String fromName = source.nameOf(activeId);

            Optional<MigrationMapping> override = overrides.stream()
                    .filter(m -> m.coveredFromIds().contains(activeId))
                    .findFirst();

            if (override.isPresent()) {
                String to = primaryTarget(override.get());
                String toType = target.has(to) ? target.typeOf(to) : null;
                entries.add(new ActivityDiffEntry(
                        activeId,
                        fromType,
                        fromName,
                        ActivityDiffEntry.Status.MAPPED_BY_OVERRIDE,
                        to,
                        toType,
                        "Mapped by the operator to '" + to + "'."));
                continue;
            }

            if (!target.has(activeId)) {
                entries.add(new ActivityDiffEntry(
                        activeId,
                        fromType,
                        fromName,
                        ActivityDiffEntry.Status.FLAGGED_UNMAPPED,
                        null,
                        null,
                        "No activity with id '" + activeId + "' exists in the target version — the engine cannot"
                                + " auto-map it. Pick a target activity for it."));
                continue;
            }

            String targetType = target.typeOf(activeId);
            if (!java.util.Objects.equals(fromType, targetType)) {
                entries.add(new ActivityDiffEntry(
                        activeId,
                        fromType,
                        fromName,
                        ActivityDiffEntry.Status.TYPE_CHANGED,
                        activeId,
                        targetType,
                        "Keeps its id but changed type (" + fromType + " → " + targetType + "). Auto-map accepts"
                                + " this and the engine will likely return success, but the token lands on different"
                                + " behavior — verify this is intended. Neither the Inspector nor Flowable flags it"
                                + " as an error."));
                continue;
            }

            if (!source.nestingPath(activeId).equals(target.nestingPath(activeId))) {
                entries.add(new ActivityDiffEntry(
                        activeId,
                        fromType,
                        fromName,
                        ActivityDiffEntry.Status.NESTING_CHANGED,
                        activeId,
                        targetType,
                        "Keeps its id and type but moved between subprocess scopes (" + source.nestingPath(activeId)
                                + " → " + target.nestingPath(activeId) + "). Auto-map accepts it; variable scoping and"
                                + " event context may differ."));
                continue;
            }

            entries.add(new ActivityDiffEntry(
                    activeId,
                    fromType,
                    fromName,
                    ActivityDiffEntry.Status.AUTO_MAPPED,
                    activeId,
                    targetType,
                    "Maps by name — same id and type in both versions."));
        }
        return entries;
    }

    private static String primaryTarget(MigrationMapping mapping) {
        return mapping.toActivityId() != null && !mapping.toActivityId().isBlank()
                ? mapping.toActivityId()
                : mapping.toActivityIds().get(0);
    }
}

package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.inspector.dto.InstanceVariables.VariableDto;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/diff/{siblingId} (SPEC §5.2) — "why did this one fail when
 * 9,999 succeeded?". A three-way read-only comparison of the failed <b>subject</b> against a
 * completed <b>sibling</b> of the same definition version, composed entirely from historic
 * queries (historic-variable-instances + historic-activity-instances — never a runtime table).
 *
 * <p>Two doctrines are baked into the shape:
 *
 * <ul>
 *   <li><b>Truncated projections only</b> (SPEC §4/§5.2): variables are diffed on the same
 *       256 KiB capped projection the ledger uses; a value over the cap is NEVER fetched in
 *       full just to compare it — the pair is flagged {@link VariableChange#DIFFER_BEYOND_PREVIEW}
 *       ("values differ beyond preview") and {@link #previewCappedPresent} is set.</li>
 *   <li><b>Shape + glyph, not hue</b> (SPEC §10a): the path divergence names the diverging
 *       activity id sets so the diagram can differentiate the two runs by stroke style +
 *       endpoint glyphs — the response carries no colour semantics.</li>
 * </ul>
 */
public record SiblingDiffResponse(
        InstanceRef subject,
        InstanceRef sibling,
        boolean sameDefinition, // false when a manually-picked sibling is a different definition
        List<VariableDelta> variables,
        PathDivergence path,
        List<TimingDelta> timings,
        boolean previewCappedPresent) { // at least one variable pair is DIFFER_BEYOND_PREVIEW

    /** Minimal identity of one side of the comparison — enough to label the panes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InstanceRef(
            String processInstanceId,
            String businessKey,
            String definitionId,
            String processDefinitionKey,
            Integer definitionVersion,
            String startTime,
            String endTime,
            Long durationMs,
            boolean ended) {}

    /** How one variable key differs between the two runs (subject-relative). */
    public enum VariableChange {
        SAME,
        CHANGED,
        ONLY_IN_SUBJECT,
        ONLY_IN_SIBLING,
        DIFFER_BEYOND_PREVIEW
    }

    /**
     * One variable key across both runs. {@code subject}/{@code sibling} carry the capped typed
     * projection (null on the absent side); the UI renders +/−/± glyphs from {@code change},
     * never colour alone.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VariableDelta(String name, VariableChange change, VariableDto subject, VariableDto sibling) {}

    /**
     * The execution paths of both runs plus the divergence sets that drive the diagram
     * stroke-style overlay. Ids only — the diagram already owns names/geometry.
     */
    public record PathDivergence(
            List<PathActivity> subjectPath,
            List<PathActivity> siblingPath,
            List<String> onlyInSubject,
            List<String> onlyInSibling,
            List<String> common) {}

    /** One historic activity on a run's path. {@code unfinished} = where the subject stalled. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PathActivity(
            String activityId,
            String activityName,
            String activityType,
            String startTime,
            String endTime,
            Long durationMs,
            boolean unfinished) {}

    /**
     * Per-activity timing, aggregated across occurrences (loops sum). A null side means the
     * activity never ran there; {@code subjectUnfinished} flags the stalled step whose duration
     * is unknowable because it never completed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimingDelta(
            String activityId,
            String activityName,
            Long subjectMs,
            Long siblingMs,
            Long deltaMs, // subjectMs - siblingMs, only when both sides completed
            int subjectOccurrences,
            int siblingOccurrences,
            boolean subjectUnfinished) {}
}

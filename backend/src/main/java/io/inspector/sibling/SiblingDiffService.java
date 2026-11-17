package io.inspector.sibling;

import io.inspector.client.ActivityInstancePage;
import io.inspector.client.ActivityInstanceRecord;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.HistoricVariableRecord;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstancePage;
import io.inspector.client.ProcessInstanceQuery;
import io.inspector.client.ProcessInstanceRecord;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
import io.inspector.dto.InstanceVariables.VariableDto;
import io.inspector.dto.NearestSiblingResponse;
import io.inspector.dto.NearestSiblingResponse.SiblingRef;
import io.inspector.dto.SiblingDiffResponse;
import io.inspector.dto.SiblingDiffResponse.InstanceRef;
import io.inspector.dto.SiblingDiffResponse.PathActivity;
import io.inspector.dto.SiblingDiffResponse.PathDivergence;
import io.inspector.dto.SiblingDiffResponse.TimingDelta;
import io.inspector.dto.SiblingDiffResponse.VariableChange;
import io.inspector.dto.SiblingDiffResponse.VariableDelta;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Sibling diff (SPEC §5.2): resolve the nearest completed sibling of a failed instance and
 * compute a three-way (variables / path / timing) comparison — all over historic queries.
 *
 * <p>Engine I/O is confined to the two resolver methods; the comparison itself is a set of
 * pure static functions over the historic projections ({@link #diffVariables},
 * {@link #divergePath}, {@link #timingDeltas}) so the join semantics are unit-testable at
 * rung 1 without a live engine (unit-test-patterns skill).
 */
@Service
public class SiblingDiffService {

    /** How many recently-completed candidates the nearest-sibling scan pulls (endTime desc). */
    static final int NEAREST_CANDIDATE_SCAN = 25;

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;
    private final InstanceDetailService detail;

    public SiblingDiffService(EngineRegistry registry, ProcessApiClient flowable, InstanceDetailService detail) {
        this.registry = registry;
        this.flowable = flowable;
        this.detail = detail;
    }

    /* ================= resolver: nearest completed sibling ================= */

    public NearestSiblingResponse nearestSibling(String engineId, String instanceId) {
        EngineConfig engine = registry.resolveOrNotFound(engineId);
        ProcessInstanceRecord historic = detail.requireHistoric(engine, instanceId);
        String definitionId = historic.processDefinitionId();
        String defKey = definitionKeyOf(definitionId);
        Integer defVersion = definitionVersionOf(definitionId);

        if (definitionId == null) {
            // No definition id on the historic row (an engine that purged the definition) —
            // siblings are unresolvable; the manual input remains the escape hatch.
            return NearestSiblingResponse.none(0, null, defKey, defVersion);
        }

        // Most recently COMPLETED instance of the SAME definition version. finished=true keeps
        // us in history (runtime has none of these) and encodes "successful" the honest way:
        // it reached an end event instead of dead-lettering.
        ProcessInstanceQuery body = ProcessInstanceQuery.builder()
                .processDefinitionId(definitionId)
                .finished(true)
                .sort("endTime", "desc")
                .size(NEAREST_CANDIDATE_SCAN)
                .tenantId(engine.tenantId())
                .build();

        ProcessInstancePage page = flowable.queryHistoricProcessInstances(engine, CallPriority.INTERACTIVE, body);
        List<ProcessInstanceRecord> rows = page.dataOrEmpty();
        // Every non-self row becomes a picker candidate — the whole scan is already paid for,
        // so offering a choice costs no extra engine I/O. The head stays the auto-suggestion.
        List<SiblingRef> candidates = new ArrayList<>(rows.size());
        for (ProcessInstanceRecord row : rows) {
            String candidateId = row.id();
            if (candidateId == null || candidateId.equals(instanceId)) continue; // never diff against self
            candidates.add(siblingRef(row));
        }
        if (!candidates.isEmpty()) {
            return new NearestSiblingResponse(
                    true, candidates.get(0), List.copyOf(candidates), rows.size(), definitionId, defKey, defVersion);
        }
        return NearestSiblingResponse.none(rows.size(), definitionId, defKey, defVersion);
    }

    /* ================= resolver: the three-way diff ================= */

    public SiblingDiffResponse diff(String engineId, String subjectId, String siblingId) {
        EngineConfig engine = registry.resolveOrNotFound(engineId);
        ProcessInstanceRecord subjectHistoric = detail.requireHistoric(engine, subjectId);
        ProcessInstanceRecord siblingHistoric = detail.requireHistoric(engine, siblingId);

        boolean sameDefinition =
                Objects.equals(subjectHistoric.processDefinitionId(), siblingHistoric.processDefinitionId());

        List<VariableDto> subjectVars = historicVariableProjection(engine, subjectId);
        List<VariableDto> siblingVars = historicVariableProjection(engine, siblingId);
        List<VariableDelta> variables = diffVariables(subjectVars, siblingVars);
        boolean previewCapped = variables.stream().anyMatch(v -> v.change() == VariableChange.DIFFER_BEYOND_PREVIEW);

        List<PathActivity> subjectPath = historicPath(engine, subjectId);
        List<PathActivity> siblingPath = historicPath(engine, siblingId);
        PathDivergence path = divergePath(subjectPath, siblingPath);
        List<TimingDelta> timings = timingDeltas(subjectPath, siblingPath);

        return new SiblingDiffResponse(
                instanceRef(subjectHistoric),
                instanceRef(siblingHistoric),
                sameDefinition,
                variables,
                path,
                timings,
                previewCapped);
    }

    /* ================= pure comparison core (rung-1 testable) ================= */

    /**
     * Variable diff, subject-relative, sorted by name. Comparison is on the CAPPED projection:
     * when either side is truncated the full blob was never fetched (SPEC §5.2), so the pair
     * cannot be asserted equal or changed — it ships {@link VariableChange#DIFFER_BEYOND_PREVIEW}.
     */
    static List<VariableDelta> diffVariables(List<VariableDto> subjectVars, List<VariableDto> siblingVars) {
        Map<String, VariableDto> subject = byName(subjectVars);
        Map<String, VariableDto> sibling = byName(siblingVars);
        Set<String> names = new TreeSet<>();
        names.addAll(subject.keySet());
        names.addAll(sibling.keySet());

        List<VariableDelta> deltas = new ArrayList<>(names.size());
        for (String name : names) {
            VariableDto s = subject.get(name);
            VariableDto o = sibling.get(name);
            VariableChange change;
            if (s == null) {
                change = VariableChange.ONLY_IN_SIBLING;
            } else if (o == null) {
                change = VariableChange.ONLY_IN_SUBJECT;
            } else if (s.masked() || o.masked()) {
                change = VariableChange.MASKED; // #742: no same/changed on a secret-named value
            } else if (s.truncated() || o.truncated()) {
                change = VariableChange.DIFFER_BEYOND_PREVIEW;
            } else if (Objects.equals(s.type(), o.type()) && Objects.equals(s.value(), o.value())) {
                change = VariableChange.SAME;
            } else {
                change = VariableChange.CHANGED;
            }
            deltas.add(new VariableDelta(name, change, s, o));
        }
        return deltas;
    }

    /**
     * Path divergence: the diverging activity-id sets that drive the diagram overlay. Ids are
     * de-duplicated but keep first-seen order; a loop that revisits an activity contributes it
     * once to the set (the timing pass keeps the per-occurrence counts).
     */
    static PathDivergence divergePath(List<PathActivity> subjectPath, List<PathActivity> siblingPath) {
        Set<String> subjectIds = orderedIds(subjectPath);
        Set<String> siblingIds = orderedIds(siblingPath);

        List<String> onlyInSubject = new ArrayList<>();
        List<String> common = new ArrayList<>();
        for (String id : subjectIds) {
            if (siblingIds.contains(id)) common.add(id);
            else onlyInSubject.add(id);
        }
        List<String> onlyInSibling = new ArrayList<>();
        for (String id : siblingIds) {
            if (!subjectIds.contains(id)) onlyInSibling.add(id);
        }
        return new PathDivergence(subjectPath, siblingPath, onlyInSubject, onlyInSibling, common);
    }

    /**
     * Per-activity timing over the UNION of activity ids (subject order first, sibling-only
     * appended). Durations sum across occurrences; a null duration (the stalled, still-open
     * step) is excluded from the sum but counted as an occurrence and flagged
     * {@code subjectUnfinished}. {@code deltaMs} is populated only when both sides completed.
     */
    static List<TimingDelta> timingDeltas(List<PathActivity> subjectPath, List<PathActivity> siblingPath) {
        Map<String, Agg> subject = aggregate(subjectPath);
        Map<String, Agg> sibling = aggregate(siblingPath);

        Set<String> order = new LinkedHashSet<>(subject.keySet());
        order.addAll(sibling.keySet());

        List<TimingDelta> deltas = new ArrayList<>(order.size());
        for (String id : order) {
            Agg s = subject.get(id);
            Agg o = sibling.get(id);
            Long subjectMs = s != null ? s.completedSum() : null;
            Long siblingMs = o != null ? o.completedSum() : null;
            Long deltaMs = subjectMs != null && siblingMs != null ? subjectMs - siblingMs : null;
            String name = s != null && s.name != null ? s.name : (o != null ? o.name : null);
            deltas.add(new TimingDelta(
                    id,
                    name,
                    subjectMs,
                    siblingMs,
                    deltaMs,
                    s != null ? s.occurrences : 0,
                    o != null ? o.occurrences : 0,
                    s != null && s.unfinished));
        }
        return deltas;
    }

    /* ================= historic projections (engine I/O) ================= */

    private List<VariableDto> historicVariableProjection(EngineConfig engine, String instanceId) {
        List<VariableDto> rows = new ArrayList<>();
        for (HistoricVariableRecord row : flowable.listHistoricVariableInstances(
                        engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                .dataOrEmpty()) {
            // Nested under "variable" on every recorded engine; a flat row is read from the row
            // itself — same as the detail ledger's historic leg.
            rows.add(InstanceDetailService.typedRow(row.envelope(), "global", row.executionId(), row.taskId()));
        }
        return rows;
    }

    private List<PathActivity> historicPath(EngineConfig engine, String instanceId) {
        ActivityInstancePage page = flowable.listHistoricActivities(
                engine, CallPriority.INTERACTIVE, instanceId, 0, engine.maxPageSizeOrDefault());
        List<PathActivity> path = new ArrayList<>();
        for (ActivityInstanceRecord row : page.dataOrEmpty()) {
            path.add(pathRow(row));
        }
        return path;
    }

    static PathActivity pathRow(ActivityInstanceRecord row) {
        String endTime = row.endTime();
        return new PathActivity(
                row.activityId(),
                row.activityName(),
                row.activityType(),
                row.startTime(),
                endTime,
                row.durationInMillis(),
                endTime == null);
    }

    /* ================= helpers ================= */

    /** Occurrence aggregate for one activity id across a run's path. */
    private static final class Agg {
        String name;
        int occurrences;
        long completed; // sum of completed durations
        boolean anyCompleted;
        boolean unfinished;

        Long completedSum() {
            return anyCompleted ? completed : null;
        }
    }

    private static Map<String, Agg> aggregate(List<PathActivity> path) {
        Map<String, Agg> byId = new LinkedHashMap<>();
        for (PathActivity a : path) {
            if (a.activityId() == null) continue;
            Agg agg = byId.computeIfAbsent(a.activityId(), k -> new Agg());
            if (agg.name == null) agg.name = a.activityName();
            agg.occurrences++;
            if (a.unfinished()) {
                agg.unfinished = true;
            } else if (a.durationMs() != null) {
                agg.completed += a.durationMs();
                agg.anyCompleted = true;
            }
        }
        return byId;
    }

    private static Set<String> orderedIds(List<PathActivity> path) {
        Set<String> ids = new LinkedHashSet<>();
        for (PathActivity a : path) {
            if (a.activityId() != null) ids.add(a.activityId());
        }
        return ids;
    }

    private static Map<String, VariableDto> byName(List<VariableDto> vars) {
        Map<String, VariableDto> map = new LinkedHashMap<>();
        for (VariableDto v : vars) {
            if (v.name() != null) map.putIfAbsent(v.name(), v);
        }
        return map;
    }

    private static SiblingRef siblingRef(ProcessInstanceRecord row) {
        return new SiblingRef(row.id(), row.businessKey(), row.startTime(), row.endTime(), row.durationInMillis());
    }

    private static InstanceRef instanceRef(ProcessInstanceRecord row) {
        String definitionId = row.processDefinitionId();
        return new InstanceRef(
                row.id(),
                row.businessKey(),
                definitionId,
                definitionKeyOf(definitionId),
                definitionVersionOf(definitionId),
                row.startTime(),
                row.endTime(),
                row.durationInMillis(),
                row.endTime() != null);
    }

    static String definitionKeyOf(String definitionId) {
        if (definitionId == null) return null;
        int i = definitionId.indexOf(':');
        return i > 0 ? definitionId.substring(0, i) : definitionId;
    }

    static Integer definitionVersionOf(String definitionId) {
        if (definitionId == null) return null;
        String[] parts = definitionId.split(":", 3);
        try {
            return parts.length >= 2 ? Integer.valueOf(parts[1]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

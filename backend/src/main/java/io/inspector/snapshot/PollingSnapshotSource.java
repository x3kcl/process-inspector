package io.inspector.snapshot;

import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.registry.EngineRegistry;
import io.inspector.triage.TriageAggregationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The v2.0 {@link SnapshotSource}: re-runs the existing Stage-0 count aggregation on the
 * BACKGROUND resilience lane (the thin {@code sampler} bulkhead) and packages ONE pass's whole
 * product — per-lane observations for the snapshot store, the aggregation-side error groups
 * for the incident ledger (ARCH §2.7), and the per-engine truncation markers. Reusing {@link
 * TriageAggregationService} keeps ONE source of truth for the FAILED/RETRYING synthesis and
 * the CMMN out-of-scope discrimination — the sampler never re-derives status semantics.
 */
@Component
public class PollingSnapshotSource implements SnapshotSource {

    private final TriageAggregationService aggregation;
    private final Clock clock;
    private final ProcessApiClient flowable;
    private final CmmnApiClient cmmn;
    private final EngineRegistry registry;
    private final InspectorProperties properties;

    /** Tests that only exercise the status-chip sample. */
    public PollingSnapshotSource(TriageAggregationService aggregation, Clock clock) {
        this(aggregation, clock, null, null, null, null);
    }

    @Autowired
    public PollingSnapshotSource(
            TriageAggregationService aggregation,
            Clock clock,
            ProcessApiClient flowable,
            CmmnApiClient cmmn,
            EngineRegistry registry,
            InspectorProperties properties) {
        this.aggregation = aggregation;
        this.clock = clock;
        this.flowable = flowable;
        this.cmmn = cmmn;
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public AggregationSample sample() {
        TriageDashboardResponse dashboard = aggregation.aggregate(CallPriority.BACKGROUND);
        List<EngineLaneCount> out = new ArrayList<>();

        // Status chips — only for engines the aggregation could read (a down engine is absent
        // from statusCountsByEngine, so it contributes no fabricated row).
        for (var byEngine : dashboard.statusCountsByEngine().entrySet()) {
            String engineId = byEngine.getKey();
            for (var byStatus : byEngine.getValue().entrySet()) {
                laneOf(byStatus.getKey())
                        .ifPresent(lane -> out.add(new EngineLaneCount(engineId, lane, byStatus.getValue())));
            }
        }

        // Out-of-scope (CMMN) dead-letters — a NULL count means "cannot discriminate" (pre-6.8),
        // NOT zero, so we write no row and the trend stays honest (SPEC §4 Stage 0). Alongside,
        // collect the R-SEM-12 truncation markers: an engine whose failure-lane scan hit the cap
        // makes every group count it contributes a lower bound — the ledger needs to know. Also
        // track whether EVERY registry engine came back ok this pass (#302, R-BAU-10): the
        // incident ledger's zero-state regression gate must never treat an unreachable engine's
        // groups as "observed absent" — a blind cycle is not a resolved one. Alongside that
        // QUALITY marker, record the pass's observation SCOPE (#372, V22): the envelope's key set
        // IS the enabled fleet the aggregation fanned out over (one atomic registry snapshot —
        // TriageAggregationService captures registry.all() once and drives both of its loops from
        // it), including engines that answered not-ok. In scope but unobserved is cycleComplete's
        // story; which engines were in scope at all is this one's, and nothing else can tell it —
        // a DISABLED engine never enters perEngine, so the cycle stays "complete" while the fleet
        // silently shrinks under the series.
        Set<String> truncatedEngines = new LinkedHashSet<>();
        Set<String> fleetEngineIds = new LinkedHashSet<>();
        boolean cycleComplete = true;
        for (var byEngine : dashboard.perEngine().entrySet()) {
            PerEngineTriage envelope = byEngine.getValue();
            fleetEngineIds.add(byEngine.getKey());
            if (!envelope.ok()) {
                cycleComplete = false;
            }
            if (envelope.ok() && envelope.outOfScopeDeadletters() != null) {
                out.add(new EngineLaneCount(
                        byEngine.getKey(), SnapshotLane.OUT_OF_SCOPE_DLQ, envelope.outOfScopeDeadletters()));
            }
            if (envelope.dlqScan() != null && envelope.dlqScan().startsWith("truncated")) {
                truncatedEngines.add(byEngine.getKey());
            }
        }
        if (fleetEngineIds.isEmpty()) {
            // #380 (the adjacent case): with NO enabled engines the loop above never executes, so
            // "did everyone answer?" was VACUOUSLY true — a pass that observed nobody claiming to
            // be a complete observation of everybody, which armed the ledger's zero-state gate for
            // every RESOLVED incident at once. This is not "the fleet answered", it is "there was
            // no fleet". #302's meaning is untouched: an engine that IS in scope and did not come
            // back ok() still makes the cycle blind, exactly as before; this only says an EMPTY
            // scope cannot be a complete observation of anything.
            cycleComplete = false;
        }
        List<DefinitionStart> topStarts = appendHistoryAndThroughput(out, fleetEngineIds);
        List<io.inspector.dto.ErrorGroup> groups =
                dashboard.errorGroups() != null ? dashboard.errorGroups() : List.of();
        return new AggregationSample(
                out, groups, clock.instant(), truncatedEngines, cycleComplete, fleetEngineIds, topStarts);
    }

    /** Count-only, per engine. A missing route writes no row. One engine's failure does not drop the pass. */
    private List<DefinitionStart> appendHistoryAndThroughput(List<EngineLaneCount> out, Set<String> engineIds) {
        if (flowable == null || registry == null) {
            return List.of();
        }
        Instant now = clock.instant();
        Duration interval = properties == null
                ? Duration.ofSeconds(60)
                : properties.snapshotOrDefault().sampleIntervalOrDefault();
        Instant after = now.minus(interval);
        List<DefinitionStart> tops = new ArrayList<>();
        for (String id : engineIds) {
            EngineConfig engine = registry.resolve(id).orElse(null);
            if (engine == null) {
                continue;
            }
            try {
                Long history = flowable.countJobsIfSupported(engine, CallPriority.BACKGROUND, JobLaneKind.HISTORY);
                if (history != null) {
                    out.add(new EngineLaneCount(id, SnapshotLane.HISTORY_BACKLOG, history));
                }
                Long started = flowable.countHistoricProcesses(engine, CallPriority.BACKGROUND, after, now, true, true);
                if (started != null) {
                    out.add(new EngineLaneCount(id, SnapshotLane.PROC_STARTED, started));
                }
                Long finished =
                        flowable.countHistoricProcesses(engine, CallPriority.BACKGROUND, after, now, false, true);
                if (finished != null) {
                    out.add(new EngineLaneCount(id, SnapshotLane.PROC_FINISHED, finished));
                }
                addIfPresent(
                        out,
                        id,
                        SnapshotLane.TASK_CREATED,
                        flowable.countHistoricTasks(engine, CallPriority.BACKGROUND, after, now, false));
                addIfPresent(
                        out,
                        id,
                        SnapshotLane.TASK_COMPLETED,
                        flowable.countHistoricTasks(engine, CallPriority.BACKGROUND, after, now, true));
                if (cmmn != null) {
                    addIfPresent(
                            out,
                            id,
                            SnapshotLane.CASE_STARTED,
                            cmmn.countCasesStarted(engine, CallPriority.BACKGROUND, after, now));
                }
                tops.addAll(topStarts(engine, after, now));
            } catch (RuntimeException ignored) {
                // status rows already recorded; this engine's extra lanes wait for the next pass
            }
        }
        return tops;
    }

    private static void addIfPresent(List<EngineLaneCount> out, String engineId, SnapshotLane lane, Long count) {
        if (count != null) {
            out.add(new EngineLaneCount(engineId, lane, count));
        }
    }

    /** At most 10 latest definition keys. The capped flag is true when the catalog page is not the whole list. */
    private List<DefinitionStart> topStarts(EngineConfig engine, Instant after, Instant now) {
        FlowablePage page = flowable.listLatestProcessDefinitions(engine, CallPriority.BACKGROUND, 10);
        List<Map<String, Object>> rows = page == null ? List.of() : page.dataOrEmpty();
        boolean capped = page != null && page.total() > rows.size();
        List<DefinitionStart> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("key");
            if (!(key instanceof String definitionKey) || definitionKey.isBlank()) {
                continue;
            }
            Long starts = flowable.countHistoricProcesses(
                    engine, CallPriority.BACKGROUND, after, now, true, true, definitionKey);
            if (starts != null) {
                out.add(new DefinitionStart(engine.id(), definitionKey, starts, capped));
            }
        }
        return out;
    }

    /** Maps a status-count key to its lane; skips any key not in the fixed lane set (defensive). */
    private static Optional<SnapshotLane> laneOf(String status) {
        for (SnapshotLane lane : SnapshotLane.values()) {
            if (lane.name().equals(status)) {
                return Optional.of(lane);
            }
        }
        return Optional.empty();
    }
}

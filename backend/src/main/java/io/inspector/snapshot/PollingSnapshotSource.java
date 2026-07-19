package io.inspector.snapshot;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.triage.TriageAggregationService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    public PollingSnapshotSource(TriageAggregationService aggregation, Clock clock) {
        this.aggregation = aggregation;
        this.clock = clock;
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
        // makes every group count it contributes a lower bound — the ledger needs to know.
        Set<String> truncatedEngines = new LinkedHashSet<>();
        for (var byEngine : dashboard.perEngine().entrySet()) {
            PerEngineTriage envelope = byEngine.getValue();
            if (envelope.ok() && envelope.outOfScopeDeadletters() != null) {
                out.add(new EngineLaneCount(
                        byEngine.getKey(), SnapshotLane.OUT_OF_SCOPE_DLQ, envelope.outOfScopeDeadletters()));
            }
            if (envelope.dlqScan() != null && envelope.dlqScan().startsWith("truncated")) {
                truncatedEngines.add(byEngine.getKey());
            }
        }
        List<io.inspector.dto.ErrorGroup> groups =
                dashboard.errorGroups() != null ? dashboard.errorGroups() : List.of();
        return new AggregationSample(out, groups, clock.instant(), truncatedEngines);
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

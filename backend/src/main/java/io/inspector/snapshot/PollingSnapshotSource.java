package io.inspector.snapshot;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.triage.TriageAggregationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The v2.0 {@link SnapshotSource}: re-runs the existing Stage-0 count aggregation on the
 * BACKGROUND resilience lane (the thin {@code sampler} bulkhead) and flattens it to per-lane
 * observations. Reusing {@link TriageAggregationService} keeps ONE source of truth for the
 * FAILED/RETRYING synthesis and the CMMN out-of-scope discrimination — the sampler never
 * re-derives status semantics.
 */
@Component
public class PollingSnapshotSource implements SnapshotSource {

    private final TriageAggregationService aggregation;

    public PollingSnapshotSource(TriageAggregationService aggregation) {
        this.aggregation = aggregation;
    }

    @Override
    public List<EngineLaneCount> sample() {
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
        // NOT zero, so we write no row and the trend stays honest (SPEC §4 Stage 0).
        for (var byEngine : dashboard.perEngine().entrySet()) {
            PerEngineTriage envelope = byEngine.getValue();
            if (envelope.ok() && envelope.outOfScopeDeadletters() != null) {
                out.add(new EngineLaneCount(
                        byEngine.getKey(), SnapshotLane.OUT_OF_SCOPE_DLQ, envelope.outOfScopeDeadletters()));
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

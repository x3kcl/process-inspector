package io.inspector.migration;

import io.inspector.migration.CohortFindingAggregator.ActivityAggregate;
import io.inspector.migration.CohortFindingAggregator.BlockedInstance;
import io.inspector.migration.CohortFindingAggregator.InstanceRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A stored cohort analysis (#495). {@code banner} is the estimate's honesty line. Blocker
 * instance ids are absent from {@code executableIds}. {@code instances} is one page of the drill.
 */
public record CohortAnalysisView(
        UUID id,
        String engineId,
        String status,
        String sourceDefinitionId,
        String targetDefinitionId,
        int taxonomyVersion,
        CohortPreviewRequest filter,
        Map<String, Integer> codeCounts,
        List<String> sampleInstanceIds,
        int blockerCount,
        int warningCount,
        int cleanCount,
        List<ActivityAggregate> byActivity,
        List<BlockedInstance> blockers,
        List<String> executableIds,
        List<InstanceRow> instances,
        int page,
        int size,
        int instanceTotal,
        long cohortTotal,
        boolean lowerBound,
        String boundLabel,
        String failure,
        String banner) {

    public static final String BANNER = "Inspector estimate — the engine is the only ground truth at execute";
}

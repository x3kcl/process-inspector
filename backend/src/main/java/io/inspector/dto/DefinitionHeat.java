package io.inspector.dto;

import java.time.Instant;
import java.util.List;

/**
 * Per-definition-version heat map and outcome funnel (DEFINITION-HEATMAP.md §6, #783). Always a
 * SAMPLE: {@code sampledInstances} of {@code finishedTotal}, minus {@code droppedInstances}. The
 * per-flow layer is deliberately not part of this release (owner decision 5, #671).
 */
public record DefinitionHeat(
        String engineId,
        String definitionId,
        Instant asOf,
        boolean cached,
        Coverage coverage,
        int requestedN,
        int sampledInstances,
        long finishedTotal,
        int droppedInstances,
        DropReasons dropReasons,
        Instant oldestEnd,
        Instant newestEnd,
        boolean partial,
        List<NodeHeat> nodes,
        List<Outcome> outcomes,
        int noEndEventCount,
        String samplingNote) {

    public enum Coverage {
        FULL,
        NO_FLOW_HISTORY,
        NO_ACTIVITY_HISTORY,
        NO_HISTORY
    }

    public enum Tier {
        DOMINANT,
        WARM,
        COOL,
        NONE
    }

    public record DropReasons(int tooManyRows, int readFailed) {}

    /** Timing fields are null when no execution of the node was timed (all unfinished). */
    public record NodeHeat(
            String activityId,
            String name,
            String type,
            int executions,
            int instances,
            double hitPct,
            Double avgMs,
            Long p50Ms,
            Long p95Ms,
            Long maxMs,
            long totalMs,
            double sharePct,
            Tier tier) {}

    public record Outcome(String endEventId, String name, int count, double pct) {}
}

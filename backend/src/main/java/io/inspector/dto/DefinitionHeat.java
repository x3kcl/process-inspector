package io.inspector.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Per-definition-version heat map and outcome funnel (DEFINITION-HEATMAP.md §6, #783). Always a
 * SAMPLE: {@code sampledInstances} of {@code finishedTotal}, minus {@code droppedInstances}. The
 * per-flow layer is deliberately not part of this release (owner decision 5, #671). {@code cached}:
 * served from the 10-minute cache, not gathered by this request; {@code refreshThrottledUntil}: set
 * only when a refresh was refused (once per 60 s per definition version, #784), null otherwise.
 */
public record DefinitionHeat(
        String engineId,
        String definitionId,
        Instant asOf,
        boolean cached,
        Coverage coverage,
        Structure structure,
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
        String samplingNote,
        Instant refreshThrottledUntil) {

    /** As aggregated: not yet served from a cache, no refresh refused. */
    public DefinitionHeat(
            String engineId,
            String definitionId,
            Instant asOf,
            boolean cached,
            Coverage coverage,
            Structure structure,
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
        this(
                engineId,
                definitionId,
                asOf,
                cached,
                coverage,
                structure,
                requestedN,
                sampledInstances,
                finishedTotal,
                droppedInstances,
                dropReasons,
                oldestEnd,
                newestEnd,
                partial,
                nodes,
                outcomes,
                noEndEventCount,
                samplingNote,
                null);
    }

    public enum Coverage {
        FULL,
        /** No sequenceFlow rows: no flow layer, and the event-gateway armed/taken split is inferred. */
        NO_FLOW_HISTORY,
        NO_ACTIVITY_HISTORY,
        NO_HISTORY
    }

    /**
     * Whether the definition's BPMN was read to tell armed event-gateway catch events from taken
     * ones. UNAVAILABLE: no XML was passed, it did not parse, or an armable catch event has no outgoing flow; such events count as today.
     */
    public enum Structure {
        RESOLVED,
        UNAVAILABLE
    }

    public enum Tier {
        DOMINANT,
        WARM,
        COOL,
        NONE
    }

    /**
     * Why instances were dropped: more rows than one page (unreadable in full), the read failed,
     * or the instance has no activity row while others in the sample do.
     */
    public record DropReasons(int tooManyRows, int readFailed, int noActivityHistory) {}

    /** Timing fields are null when no execution of the node was timed (all unfinished). */
    public record NodeHeat(
            String activityId,
            String name,
            String type,
            int executions,
            int instances,
            double hitPct,
            int armedNotTaken,
            Double avgMs,
            Long p50Ms,
            Long p95Ms,
            Long maxMs,
            long totalMs,
            double sharePct,
            Tier tier) {}

    /** Named apart: the bare schema name "Outcome" is the access-mapping admin DTO in the OpenAPI contract. */
    @Schema(name = "HeatOutcome")
    public record Outcome(String endEventId, String name, int count, double pct) {}
}

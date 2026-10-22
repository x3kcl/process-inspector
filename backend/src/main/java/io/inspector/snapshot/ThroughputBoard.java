package io.inspector.snapshot;

import java.util.List;

/** Sampled throughput for one engine. Null current means that lane has no samples yet. */
public record ThroughputBoard(
        ThroughputTile starts10m,
        ThroughputTile starts1h,
        ThroughputTile starts24h,
        ThroughputTile finishes10m,
        ThroughputTile tasksCreated10m,
        ThroughputTile tasksCompleted10m,
        ThroughputTile casesStarted10m,
        List<String> topDefinitionKeys,
        boolean definitionKeysCapped) {

    public ThroughputBoard {
        topDefinitionKeys = topDefinitionKeys == null ? List.of() : List.copyOf(topDefinitionKeys);
    }

    public record ThroughputTile(Long current, Long reference) {}
}

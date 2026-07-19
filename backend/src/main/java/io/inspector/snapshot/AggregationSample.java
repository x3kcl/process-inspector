package io.inspector.snapshot;

import io.inspector.dto.ErrorGroup;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One point-in-time product of a SINGLE Stage-0 aggregation pass (ARCH §2.7): the per-lane
 * counts the snapshot store upserts AND the aggregation-side {@link ErrorGroup} list the
 * incident ledger consumes — computed together so the ledger adds ZERO engine calls.
 *
 * <p>{@code errorGroups} are the aggregation-side groups (ack overlay always absent — the
 * decorator joins acks per REQUEST, never inside the aggregation; see {@code ErrorGroup} doc).
 *
 * <p>{@code truncatedEngineIds} carries the R-SEM-12 honesty marker at group granularity's
 * SOURCE: the engines whose failure-lane scan hit the cap this pass ({@code dlqScan =
 * "truncated@N"}). {@link ErrorGroup} itself has no truncation flag — its counts are lower
 * bounds exactly when one of its engines is in this set — so the ledger derives a group's
 * truncation as {@code countsByEngine ∩ truncatedEngineIds ≠ ∅} (INCIDENT-LEDGER.md §3
 * {@code last_truncated} / occurrence {@code truncated}).
 *
 * <p>{@code sampledAt} is the source's observation instant (un-bucketed); the sampler floors
 * it to the store's bucket grid at write time.
 */
public record AggregationSample(
        List<EngineLaneCount> laneCounts,
        List<ErrorGroup> errorGroups,
        Instant sampledAt,
        Set<String> truncatedEngineIds) {

    public AggregationSample {
        laneCounts = laneCounts != null ? List.copyOf(laneCounts) : List.of();
        errorGroups = errorGroups != null ? List.copyOf(errorGroups) : List.of();
        truncatedEngineIds = truncatedEngineIds != null ? Set.copyOf(truncatedEngineIds) : Set.of();
    }
}

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
 *
 * <p>{@code cycleComplete} is the R-BAU-10 blind-cycle honesty marker (issue #302): true only
 * when EVERY registry engine's envelope came back {@code ok()} this pass. A group's absence
 * from {@code errorGroups} is a genuine "observed zero" only when the cycle was complete — if
 * the group's owning engine was unreachable this pass, the group is simply unobserved, not
 * resolved. {@link io.inspector.incident.IncidentLedgerService}'s zero-state regression gate
 * (INCIDENT-LEDGER.md §5) is the ONLY consumer of this flag: it must never arm from a blind
 * cycle. Nothing else in the ledger's ingestion changes on an incomplete cycle — live groups
 * still update totals/occurrence honestly (same rule as {@code truncatedEngineIds}).
 *
 * <p>{@code fleetEngineIds} is the pass's observation SCOPE (V22, #372, ALARM-COST-MODEL §16):
 * the ENABLED engines this pass fanned out over — {@code registry.all()}, INCLUDING engines whose
 * envelope came back not-ok (in scope but unobserved is precisely what {@code cycleComplete}
 * reports; scope is the INTENT set and is always known, even when quality is not). It is
 * orthogonal to both markers above and it closes the hole they cannot see: a registry DISABLE
 * removes an engine from {@code registry.all()} entirely, so the pass never fails to hear from
 * anyone and {@code cycleComplete} stays TRUE while the counts silently lose that engine's
 * members. Zero new engine calls — it is read off the envelope the sampler already iterates.
 */
public record AggregationSample(
        List<EngineLaneCount> laneCounts,
        List<ErrorGroup> errorGroups,
        Instant sampledAt,
        Set<String> truncatedEngineIds,
        boolean cycleComplete,
        Set<String> fleetEngineIds) {

    public AggregationSample {
        laneCounts = laneCounts != null ? List.copyOf(laneCounts) : List.of();
        errorGroups = errorGroups != null ? List.copyOf(errorGroups) : List.of();
        truncatedEngineIds = truncatedEngineIds != null ? Set.copyOf(truncatedEngineIds) : Set.of();
        fleetEngineIds = fleetEngineIds != null ? Set.copyOf(fleetEngineIds) : Set.of();
    }

    /**
     * Convenience for call sites that don't care about observation SCOPE (unit tests exercising
     * ingestion logic unrelated to the fleet marker) — defaults to an UNRECORDED scope, i.e. the
     * fail-closed value: rows written from it are comparable to nothing (§16.5). Production
     * ({@link PollingSnapshotSource}) always uses the canonical 6-arg constructor with the REAL
     * fan-out set; never rely on this default there.
     */
    public AggregationSample(
            List<EngineLaneCount> laneCounts,
            List<ErrorGroup> errorGroups,
            Instant sampledAt,
            Set<String> truncatedEngineIds,
            boolean cycleComplete) {
        this(laneCounts, errorGroups, sampledAt, truncatedEngineIds, cycleComplete, Set.of());
    }

    /**
     * Convenience for call sites that don't care about cycle completeness (most existing unit
     * tests exercising ingestion logic unrelated to the regression gate) — defaults to a
     * complete cycle and an UNRECORDED scope.
     */
    public AggregationSample(
            List<EngineLaneCount> laneCounts,
            List<ErrorGroup> errorGroups,
            Instant sampledAt,
            Set<String> truncatedEngineIds) {
        this(laneCounts, errorGroups, sampledAt, truncatedEngineIds, true, Set.of());
    }

    /** The canonical scope string persisted onto every occurrence row this sample writes. */
    public String canonicalFleet() {
        return FleetScope.canonical(fleetEngineIds);
    }
}

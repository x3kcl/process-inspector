package io.inspector.attention;

import java.time.Instant;
import java.util.List;

/**
 * One error class's ledger-derived evidence for the attention score (ALARM-COST-MODEL.md §6) —
 * everything the score reads, and NOTHING that requires an engine call.
 *
 * @param lastSeen the ledger's {@code last_seen} for this class; {@code null} when the class has
 *     no ledger row at all (ingestion off, or a class first observed this very cycle) — the
 *     recency factor then reads NEUTRAL rather than inventing an age.
 * @param arrivals sum of positive occurrence-total deltas over the trailing F window, counting
 *     only samples whose BOTH endpoints were fully observed (a truncated sample is a floor and a
 *     blind sample is missing an engine — neither may be differenced against), and seeding the
 *     baseline at 0 for the incident's own FIRST EVER row (a class's birth IS an arrival).
 * @param arrivalsUnknown true when the window contained differenceable samples but EVERY one of
 *     them was discarded as untrustworthy — the class's arrival volume is unknown, not zero.
 *     {@code F} then degrades to the multiplicative identity, never to a zero that would erase
 *     the whole score (§4.1's degradation rule; the review's confirmed defect was that a
 *     permanently scan-capped engine zeroed exactly its largest classes).
 * @param discardedArrivalSamples how many differenceable samples in the window were thrown away
 *     for truncation or blindness. Zero on a fully-observed window; carried so the tooltip can
 *     say "the arrival evidence is partial" instead of presenting a floor as a level.
 * @param burstArrivals the SUBSET of {@code arrivals} whose deltas landed in the trailing burst
 *     window {@code (asOf−W, asOf]} (ALARM-COST-MODEL §4.1a, #365). This is a PARTITION of the
 *     very deltas {@code arrivals} already counts — never a second, independent measurement — so
 *     {@code outside_W = arrivals − burstArrivals} and every arrival is banked exactly once, at
 *     weight 1 or weight gamma. It is bounded by {@code arrivals} in SQL (same filters, narrower
 *     time) and clamped again in the calculator.
 * @param priorBurstArrivals the same sum over the PRECEDING window {@code (asOf−2W, asOf−W]}. It
 *     feeds the Schmitt gate's hold leg ONLY and is never added to the score: summing it with
 *     {@code burstArrivals} would open a back-door entry (6 + 6 across 20 minutes is not a
 *     10-minute flood).
 * @param burstUnknown true when the burst bin held differenceable samples but EVERY one of them
 *     was untrustworthy (truncated or blind). The gate is then forced OFF and {@code F} stays at
 *     its shipped value: the tooltip says "recent arrival rate unknown", never "not spiking".
 *     Because the burst term can only ever RAISE {@code F}, an unknown bin suppresses a
 *     promotion and can never demote — strictly safer than the F2 defect class it inherits from.
 * @param discardedBurstSamples how many burst-bin samples were thrown away, for the same reason
 *     {@code discardedArrivalSamples} carries its own count.
 * @param closedEpisodeSeconds durations of this class's CLOSED episodes only. Empty is the
 *     measured norm today (zero episodes have ever closed in the pilot ledger).
 */
public record ClassHistory(
        Instant lastSeen,
        long arrivals,
        boolean arrivalsUnknown,
        long discardedArrivalSamples,
        long burstArrivals,
        long priorBurstArrivals,
        boolean burstUnknown,
        long discardedBurstSamples,
        List<Long> closedEpisodeSeconds) {

    public ClassHistory {
        closedEpisodeSeconds = closedEpisodeSeconds == null ? List.of() : List.copyOf(closedEpisodeSeconds);
    }

    /**
     * The pre-#365 five-arg shape — no burst evidence, so the gate can never fire and {@code F}
     * is byte-identical to the shipped formula (unit-test-patterns: no constructor churn).
     */
    public ClassHistory(
            Instant lastSeen,
            long arrivals,
            boolean arrivalsUnknown,
            long discardedArrivalSamples,
            List<Long> closedEpisodeSeconds) {
        this(lastSeen, arrivals, arrivalsUnknown, discardedArrivalSamples, 0L, 0L, false, 0L, closedEpisodeSeconds);
    }

    /** Evidence from a FULLY observed window — nothing was discarded, so nothing is unknown. */
    public static ClassHistory observed(Instant lastSeen, long arrivals, List<Long> closedEpisodeSeconds) {
        return new ClassHistory(lastSeen, arrivals, false, 0L, closedEpisodeSeconds);
    }

    /** The no-history class: every discriminating factor reads neutral (§4.1 degradation rule). */
    public static ClassHistory none() {
        return observed(null, 0L, List.of());
    }
}

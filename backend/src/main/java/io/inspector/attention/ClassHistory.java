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
 * @param closedEpisodeSeconds durations of this class's CLOSED episodes only. Empty is the
 *     measured norm today (zero episodes have ever closed in the pilot ledger).
 */
public record ClassHistory(
        Instant lastSeen,
        long arrivals,
        boolean arrivalsUnknown,
        long discardedArrivalSamples,
        List<Long> closedEpisodeSeconds) {

    public ClassHistory {
        closedEpisodeSeconds = closedEpisodeSeconds == null ? List.of() : List.copyOf(closedEpisodeSeconds);
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

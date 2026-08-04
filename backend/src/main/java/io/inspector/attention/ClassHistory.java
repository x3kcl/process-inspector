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
 * @param arrivals sum of positive occurrence-total deltas over the trailing F window, with deltas
 *     across a truncated boundary discarded (R-SEM-12 floors never manufacture arrivals).
 * @param closedEpisodeSeconds durations of this class's CLOSED episodes only. Empty is the
 *     measured norm today (zero episodes have ever closed in the pilot ledger).
 */
public record ClassHistory(Instant lastSeen, long arrivals, List<Long> closedEpisodeSeconds) {

    public ClassHistory {
        closedEpisodeSeconds = closedEpisodeSeconds == null ? List.of() : List.copyOf(closedEpisodeSeconds);
    }

    /** The no-history class: every discriminating factor reads neutral (§4.1 degradation rule). */
    public static ClassHistory none() {
        return new ClassHistory(null, 0L, List.of());
    }
}

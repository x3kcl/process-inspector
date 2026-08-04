package io.inspector.selfheal;

import java.time.Duration;
import java.time.Instant;

/**
 * One extracted RETRYING spell (RETRYING-RISK-LANE.md §3.1) — the unit of self-heal evidence.
 * {@code duration} spans the FIRST to the LAST sample observed with {@code retryingCount > 0}
 * (±1-bucket uncertainty, the resolution floor documented in §3.1 — a spell shorter than one
 * sampler bucket is invisible by construction).
 */
public record RetrySpell(
        Instant start,
        Instant end,
        Duration duration,
        Outcome outcome,
        boolean confounded,
        boolean gapVoided,
        boolean truncationTainted,
        boolean live) {

    /** Judged over the spell PLUS one bucket after its end (§3.1's outcome look-ahead). */
    public enum Outcome {
        SELF_HEALED,
        ESCALATED,
        /** Live spell, or a completed spell whose +1-bucket look-ahead sample is unavailable. */
        UNKNOWN
    }

    /** Counts toward {@code n} (completed, judged, and free of every exclusion taint). */
    public boolean countable() {
        return !live && !confounded && !gapVoided && !truncationTainted && outcome != Outcome.UNKNOWN;
    }

    /** Completed but excluded from {@code n} — surfaced in {@code excludedSpells}, never dropped silently. */
    public boolean excluded() {
        return !live && (confounded || gapVoided || truncationTainted);
    }
}

package io.inspector.selfheal;

import java.time.Duration;
import java.time.Instant;

/**
 * One extracted RETRYING spell (RETRYING-RISK-LANE.md §3.1) — the unit of self-heal evidence.
 * {@code duration} spans the FIRST to the LAST sample observed with {@code retryingCount > 0}
 * (±1-bucket uncertainty, the resolution floor documented in §3.1 — a spell shorter than one
 * sampler bucket is invisible by construction).
 *
 * <p>{@code leftCensored} marks a spell already in progress at the very first sample of the
 * series (window start, or the row cap): its {@code dlqAtStart} is a MID-spell dead-letter
 * level, so an escalation that happened in the unobserved prefix reads as a clean SELF_HEALED,
 * and the measured duration is short by that prefix (biasing p50/p90 low). Censored spells are
 * excluded from {@code n} and counted in {@code excludedSpells} — the §3.1 "exclusions are
 * counted and surfaced, never silently dropped" rule.
 */
public record RetrySpell(
        Instant start,
        Instant end,
        Duration duration,
        Outcome outcome,
        boolean confounded,
        boolean gapVoided,
        boolean truncationTainted,
        boolean live,
        boolean leftCensored) {

    /** Judged over the spell PLUS one bucket after its end (§3.1's outcome look-ahead). */
    public enum Outcome {
        SELF_HEALED,
        ESCALATED,
        /** Live spell, or a completed spell whose +1-bucket look-ahead sample is unavailable. */
        UNKNOWN
    }

    /** Counts toward {@code n} (completed, judged, and free of every exclusion taint). */
    public boolean countable() {
        return !live && !confounded && !gapVoided && !truncationTainted && !leftCensored && outcome != Outcome.UNKNOWN;
    }

    /** Completed but excluded from {@code n} — surfaced in {@code excludedSpells}, never dropped silently. */
    public boolean excluded() {
        return !live && (confounded || gapVoided || truncationTainted || leftCensored);
    }
}

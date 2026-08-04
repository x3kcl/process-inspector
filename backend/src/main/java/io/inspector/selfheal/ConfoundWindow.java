package io.inspector.selfheal;

import java.time.Instant;

/**
 * One audit-side confound window (RETRYING-RISK-LANE.md §3.3): the ±2-sampler-bucket range
 * around a successful {@code retry-job} audit row (single-target or bulk item, any scope) on
 * an engine the class touches. A spell overlapping ANY such window is evidence about operator
 * retries, not autonomous healing, and is excluded from {@code n} (never silently — surfaced
 * via {@code excludedSpells}).
 */
public record ConfoundWindow(Instant from, Instant to) {

    public boolean overlaps(Instant spellStart, Instant spellEnd) {
        return !from.isAfter(spellEnd) && !to.isBefore(spellStart);
    }
}

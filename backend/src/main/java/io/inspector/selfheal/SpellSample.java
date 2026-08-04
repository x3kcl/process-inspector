package io.inspector.selfheal;

import java.time.Instant;

/**
 * One class's occurrence-series point, narrowed to exactly what {@link RetrySpellExtractor}
 * needs (RETRYING-RISK-LANE.md §3.1) — decoupled from the {@code incident_occurrence} JPA
 * entity so the extractor stays a zero-Spring, zero-JPA pure transform (rung 1,
 * unit-test-patterns skill): fixtures are plain records, not a persistence context.
 */
public record SpellSample(Instant sampledAt, long deadLetterCount, long retryingCount, boolean truncated) {}

package io.inspector.selfheal;

import java.time.Instant;

/**
 * One class's occurrence-series point, narrowed to exactly what {@link RetrySpellExtractor}
 * needs (RETRYING-RISK-LANE.md §3.1) — decoupled from the {@code incident_occurrence} JPA
 * entity so the extractor stays a zero-Spring, zero-JPA pure transform (rung 1,
 * unit-test-patterns skill): fixtures are plain records, not a persistence context.
 *
 * <p>Both honesty markers travel with the point, and for the same reason: {@code truncated} says
 * the counts are a scan-cap FLOOR, {@code cycleComplete=false} says an engine was unreachable
 * when they were taken (#302, V21) so members hosted there are simply MISSING. A blind sample's
 * {@code retryingCount = 0} is not "the spell ended" and its {@code deadLetterCount} is not a
 * DLQ level — see {@link RetrySpellExtractor}.
 *
 * <p>{@code fleet} (V22, #372) is the row's observation SCOPE — the canonical sorted enabled-engine
 * id set the writing pass fanned out over — and it travels for a THIRD reason: both markers above
 * can be perfectly clean across a registry disable, because the pass is honestly complete for its
 * new, smaller fleet. Two samples with different fleets are not two levels of one series; an edge
 * between them may be an artifact of the composition change. {@code ""} = scope unrecorded.
 */
public record SpellSample(
        Instant sampledAt,
        long deadLetterCount,
        long retryingCount,
        boolean truncated,
        boolean cycleComplete,
        String fleet) {

    public SpellSample {
        fleet = fleet != null ? fleet : "";
    }
}

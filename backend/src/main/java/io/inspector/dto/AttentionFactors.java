package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The four multiplicative factors behind an {@link AttentionScore}, plus the raw evidence each
 * was derived from (ALARM-COST-MODEL.md §4.1, #353). Shipped next to the score BY DESIGN: a
 * score no tooltip can explain is a rejected design (§9 "Explainability"), so the UI renders the
 * rationale with REAL numbers rather than vibes.
 *
 * <pre>
 * A(c) = F(c) · R(c) · M(c) · S(c)
 *
 * F = log2(1 + arrivals28d)                 frequency  (positive occurrence-total deltas)
 * R = 2^(-age(lastSeen) / tau)              recency    (tau default 24h = the quiet window)
 * M = clamp(medMTTR(c) / medMTTR(fleet), lo, hi)  historic cost (neutral 1 below the floor)
 * S = max(1 - p_heal(c), floor)             self-heal demotion (neutral 1 with no R2 lane)
 * </pre>
 *
 * <p>{@code medianMttrSeconds} is absent below {@code inspector.triage.attention.min-closed-episodes}
 * — an estimate under its own sample-size floor renders as "no history", NEVER as a number
 * (ALARM-COST-MODEL §6 "estimation honesty rails"). {@code selfHealLane} mirrors the DISPLAYED
 * (server-dwelled) R2 lane the S factor consumed, or is absent when no R2 statistic applied.
 * {@code insufficientHistory} is true exactly when BOTH discriminating factors were neutral by
 * absence (M and S) — i.e. the score carries no history-derived signal at all and the ordering
 * degrades to the count-only tie-break.
 *
 * <p>{@code arrivalsUnknown} / {@code discardedArrivalSamples} are the F factor's own honesty
 * rail (review fix, ALARM-COST-MODEL §6 correction). An occurrence sample that was TRUNCATED
 * (R-SEM-12 floor) or BLIND (#302, an engine was unreachable) may not be differenced against, so
 * it is discarded — {@code discardedArrivalSamples} says how many were. When EVERY differenceable
 * sample in the window was discarded, {@code arrivalsUnknown} is true and {@code frequency} is
 * the NEUTRAL 1 rather than {@code log2(1 + 0) = 0}: {@code arrivals28d} is then "unknown", not
 * "none", and a tooltip must say so instead of reporting a zero the data never showed. Both
 * default to a fully-observed window ({@code false} / {@code 0}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttentionFactors(
        double frequency,
        double recency,
        double mttr,
        double selfHeal,
        long arrivals28d,
        long ageSeconds,
        Long medianMttrSeconds, // absent below the closed-episode floor (neutral M)
        int closedEpisodes,
        String selfHealLane, // absent when no R2 statistic was available
        boolean insufficientHistory,
        boolean arrivalsUnknown, // the whole F window was untrusted ⇒ frequency reads neutral 1
        long discardedArrivalSamples) {}

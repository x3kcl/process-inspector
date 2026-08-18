package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The factors behind an {@link AttentionScore}, plus the raw evidence each was derived from
 * (ALARM-COST-MODEL.md §4.1, #353). Shipped next to the score BY DESIGN: a score no tooltip can
 * explain is a rejected design (§9 "Explainability"), so the UI renders the rationale with REAL
 * numbers rather than vibes.
 *
 * <pre>
 * A(c) = F(c) · R(c) · S(c)                 -- M is identically 1 in v1 (#399, §17)
 *
 * F = log2(1 + arrivals28d)                 frequency  (positive occurrence-total deltas)
 * R = 2^(-age(lastSeen) / tau)              recency    (tau default 24h = the quiet window)
 * M = clamp(medMTTR(c) / medMTTR(fleet), lo, hi)  MEASURED and reported, never multiplied in
 * S = max(1 - p_heal(c), floor)             self-heal demotion (neutral 1 with no R2 lane)
 * </pre>
 *
 * <p><b>{@code mttr} is EVIDENCE, not an ordering input</b> (#399, epic #398, §17). It still
 * carries the real clamped {@code medMTTR(c)/medMTTR(fleet)} ratio, because that measurement is
 * honest and the rationale already quotes its median — but the v1 score does not consume it:
 * {@code medMTTR} spans first sighting → operator resolve, so it contains the operator's queue
 * wait and is ENDOGENOUS to the ordering it would feed. Reading {@code mttr != 1} here therefore
 * says "this class's episodes ran longer/shorter than the fleet's", never "this factor moved this
 * card". No UI may claim the latter.
 *
 * <p>{@code medianMttrSeconds} is absent below {@code inspector.triage.attention.min-closed-episodes}
 * — an estimate under its own sample-size floor renders as "no history", NEVER as a number
 * (ALARM-COST-MODEL §6 "estimation honesty rails"); when present it is the median of
 * first-sighting-to-operator-resolve durations, queue wait included (§4.1). {@code selfHealLane}
 * mirrors the DISPLAYED (server-dwelled) R2 lane the S factor consumed, or is absent when no R2
 * statistic applied. {@code insufficientHistory} is true exactly when NEITHER history-derived
 * estimate had evidence (no class median AND no usable R2 lane) — since #399 only the second of
 * those two is a score term, so it reads "this class has no ledger history to speak of", and a
 * class with closed episodes but no lane reports {@code false} while still scoring on F·R alone.
 *
 * <p>{@code arrivalsUnknown} / {@code discardedArrivalSamples} are the F factor's own honesty
 * rail (review fix, ALARM-COST-MODEL §6 correction). An occurrence sample that was TRUNCATED
 * (R-SEM-12 floor) or BLIND (#302, an engine was unreachable) may not be differenced against, so
 * it is discarded — {@code discardedArrivalSamples} says how many were. When EVERY differenceable
 * sample in the window was discarded, {@code arrivalsUnknown} is true and {@code frequency} is
 * the NEUTRAL 1 rather than {@code log2(1 + 0) = 0}: {@code arrivals28d} is then "unknown", not
 * "none", and a tooltip must say so instead of reporting a zero the data never showed. Both
 * default to a fully-observed window ({@code false} / {@code 0}).
 *
 * <p><b>The burst block</b> ({@code flooding} / {@code burstArrivals} / {@code burstWindowSeconds}
 * / {@code burstUnknown} / {@code discardedBurstSamples}) is the #365 amendment's own evidence
 * (ALARM-COST-MODEL §4.1a). {@code F} is a 28-day VOLUME measure, so a class that took its whole
 * volume in the last ten minutes used to be indistinguishable from one that trickled it over four
 * weeks — while ISA-18.2 defines a flood on exactly that ten-minute peak window. When
 * {@code flooding} is true the frequency reads {@code log2(1 + outside_W + gamma·burst_W)}: a
 * DECOMPOSITION of the arrivals already counted, never a bolt-on multiplier, so every arrival is
 * banked exactly once at weight 1 or weight gamma. {@code burstUnknown} is the bin's own honesty
 * rail — samples, but not one trustworthy — and it forces the gate OFF, leaving {@code frequency}
 * at its un-boosted value: an unknown bin can suppress a promotion, never cause a demotion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttentionFactors(
        double frequency,
        double recency,
        double mttr, // #399: measured + reported, NOT a term of the score
        double selfHeal,
        long arrivals28d,
        long ageSeconds,
        Long medianMttrSeconds, // absent below the closed-episode floor; queue wait included (§4.1)
        int closedEpisodes,
        String selfHealLane, // absent when no R2 statistic was available
        boolean insufficientHistory,
        boolean arrivalsUnknown, // the whole F window was untrusted ⇒ frequency reads neutral 1
        long discardedArrivalSamples,
        boolean flooding, // the §4.1a Schmitt gate fired ⇒ frequency carries the burst weight
        long burstArrivals, // the SUBSET of arrivals28d that landed inside the burst window
        long burstWindowSeconds, // W, so the UI never has to guess what "recent" meant
        boolean burstUnknown, // the burst bin had samples but not one trustworthy ⇒ gate off
        long discardedBurstSamples) {}

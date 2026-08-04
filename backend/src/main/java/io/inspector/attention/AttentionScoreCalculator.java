package io.inspector.attention;

import io.inspector.dto.AttentionFactors;
import io.inspector.dto.AttentionScore;
import io.inspector.dto.SelfHealStats;
import io.inspector.selfheal.SelfHealLane;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The attention score's PURE math (ALARM-COST-MODEL.md §4.1, #353) — no Spring, no stores, no
 * clock of its own, so every branch is provable against synthetic inputs (rung-1 doctrine).
 *
 * <pre>
 * A(c) = F(c) · R(c) · M(c) · S(c)
 *
 * F(c) = log2(1 + arrivals_28d(c))
 * R(c) = 2^(-age(lastSeen(c)) / tau)
 * M(c) = clamp(medMTTR(c) / medMTTR(fleet), 0.5, 2)   -- neutral 1 below the episode floor
 * S(c) = max(1 - p_heal(c), 0.25)                     -- neutral 1 with no R2 lane
 * </pre>
 *
 * <p><b>The neutrality property this whole slice rests on.</b> Every discriminating factor
 * degrades to a MULTIPLICATIVE IDENTITY when its evidence is missing: no closed episodes ⇒ M = 1,
 * no R2 lane ⇒ S = 1, no ledger row ⇒ R = 1 and F = 0. So with an empty ledger every class scores
 * exactly 0.0, the comparison falls through to the tie-break, and the tie-break IS today's
 * ordering ({@code total DESC}, then {@code signatureHash ASC} for the R-SEM-23 deterministic
 * total order). That is not an accident to be tolerated — it is the design's stated guarantee
 * (§4.1) and its measured result (§5.5: Kendall tau = 1.0 versus count-only across all 21,229
 * recorded buckets, zero position changes), and {@code AttentionOrderingNeutralityTest} proves it.
 *
 * <p><b>{@code eff(c)} is deliberately absent</b> (§3.1, adopted panel fix): per-class mitigation
 * effectiveness cannot be attributed for single-job verbs (redacted audit payloads carry no
 * signature), so in v1 it would collapse to a fleet-wide prior — a constant factor with an
 * honesty problem. It parameterizes the conceptual cost model, never the v1 ordering.
 *
 * <p><b>The S factor reads the LANE, never a raw rate.</b> §4.1 requires the stabilized,
 * hysteresis-applied value so the ordering cannot flap while the badge holds. #351 ships exactly
 * one stabilized artifact — the server-dwelled DISPLAYED lane — while its Wilson bounds are a
 * per-read point statistic, so the adapter maps lane → {@code p_heal} at the §4.1 band midpoints.
 * This is the "adapt the join, never re-derive the statistic" contract of §4.2. {@code
 * SELF_HEAL_LIKELY} lands exactly on the 0.25 floor: a proven self-healer is demoted 4x, never
 * zeroed, so a mass self-heal class still renders (same doctrine as never-hide).
 */
public final class AttentionScoreCalculator {

    /**
     * Lane → {@code p_heal}, at the midpoint of each §4.1 band ({@code LIKELY} enters at Wilson
     * LB ≥ 0.70, {@code UNLIKELY} at UB ≤ 0.30). Constants, not knobs: they are a restatement of
     * the R2 lane definition, and a deployment that wants to retune demotion has the
     * {@code self-heal-floor} clamp.
     */
    private static final double P_HEAL_LIKELY = 0.75;

    private static final double P_HEAL_MIXED = 0.50;
    private static final double P_HEAL_UNLIKELY = 0.15;

    private AttentionScoreCalculator() {}

    /**
     * @param liveTotal the class's rendered member count — the tie-break key and the rationale's
     *     first clause; never a score factor (that is exactly the count-only ordering the model
     *     replaces)
     * @param history the class's ledger evidence; {@link ClassHistory#none()} for an unknown class
     * @param fleetMedianMttrSeconds the fleet-wide median closed-episode duration, or {@code null}
     *     when the fleet has none — M then reads neutral for everyone
     * @param selfHeal the R2 statistic, or {@code null} when none applied
     */
    public static AttentionScore score(
            long liveTotal,
            ClassHistory history,
            Long fleetMedianMttrSeconds,
            SelfHealStats selfHeal,
            AttentionConfig config,
            Instant now) {
        ClassHistory evidence = history != null ? history : ClassHistory.none();

        long arrivals = Math.max(0, evidence.arrivals());
        double frequency = log2(1 + arrivals);

        long ageSeconds = ageSeconds(evidence.lastSeen(), now);
        double recency = recency(ageSeconds, config.recencyHalfLife());

        List<Long> closed = evidence.closedEpisodeSeconds();
        boolean mttrKnown = closed.size() >= config.minClosedEpisodes();
        Long classMedian = mttrKnown ? Quantiles.median(closed) : null;
        double mttr = mttrFactor(classMedian, fleetMedianMttrSeconds, config);

        SelfHealLane lane = AttentionRationale.laneOf(selfHeal);
        double selfHealFactor = selfHealFactor(lane, config.selfHealFloor());

        double score = frequency * recency * mttr * selfHealFactor;
        boolean insufficient = classMedian == null && (lane == null || lane == SelfHealLane.INSUFFICIENT_HISTORY);

        AttentionFactors factors = new AttentionFactors(
                frequency,
                recency,
                mttr,
                selfHealFactor,
                arrivals,
                ageSeconds,
                classMedian,
                closed.size(),
                lane != null ? lane.name() : null,
                insufficient);
        return new AttentionScore(
                score,
                factors,
                AttentionRationale.sentence(liveTotal, ageSeconds, classMedian, selfHeal),
                suggestedAckExpirySeconds(closed, config));
    }

    /**
     * The §3.2 ack-expiry SUGGESTION: an ack is a bet that attention is not needed for a while, so
     * the honest proxy for "how long" is the class's P75 closed-episode duration — "episodes of
     * this class usually resolve within X". Below the same closed-episode floor the M factor uses,
     * there is NO suggestion (today's behavior: the UI's initial selection stays {@code none}).
     * The operator always overrides and the R-BAU-01 resurface guarantees are untouched.
     */
    static Long suggestedAckExpirySeconds(List<Long> closedEpisodeSeconds, AttentionConfig config) {
        if (closedEpisodeSeconds.size() < config.minClosedEpisodes()) {
            return null;
        }
        return Quantiles.percentile(closedEpisodeSeconds, 0.75);
    }

    /** {@code 2^(-age/tau)} — 1 at age 0, 0.5 at one half-life. A null/future lastSeen reads 1. */
    static double recency(long ageSeconds, Duration halfLife) {
        long tau = Math.max(1, halfLife.toSeconds());
        if (ageSeconds <= 0) {
            return 1.0;
        }
        return Math.pow(2.0, -((double) ageSeconds / tau));
    }

    /** Neutral 1 whenever either side of the ratio is unknown or degenerate — never a guess. */
    static double mttrFactor(Long classMedianSeconds, Long fleetMedianSeconds, AttentionConfig config) {
        if (classMedianSeconds == null || fleetMedianSeconds == null || fleetMedianSeconds <= 0) {
            return 1.0;
        }
        double ratio = (double) classMedianSeconds / fleetMedianSeconds;
        return Math.max(config.mttrClampLow(), Math.min(config.mttrClampHigh(), ratio));
    }

    /** {@code max(1 - p_heal, floor)}; an absent or insufficient lane is neutral 1. */
    static double selfHealFactor(SelfHealLane lane, double floor) {
        if (lane == null || lane == SelfHealLane.INSUFFICIENT_HISTORY) {
            return 1.0;
        }
        double pHeal =
                switch (lane) {
                    case SELF_HEAL_LIKELY -> P_HEAL_LIKELY;
                    case SELF_HEAL_MIXED -> P_HEAL_MIXED;
                    case SELF_HEAL_UNLIKELY -> P_HEAL_UNLIKELY;
                    case INSUFFICIENT_HISTORY -> 0.0;
                };
        return Math.max(floor, 1.0 - pHeal);
    }

    private static long ageSeconds(Instant lastSeen, Instant now) {
        if (lastSeen == null) {
            return 0L; // no ledger row ⇒ "as fresh as it gets", never a fabricated age
        }
        return Math.max(0L, Duration.between(lastSeen, now).toSeconds());
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}

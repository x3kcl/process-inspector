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
 * A(c) = F(c) · R(c) · S(c)                           -- M is IDENTICALLY 1 in v1 (#399, §17)
 *
 * F(c) = log2(1 + arrivals_28d(c))                    -- neutral 1 when the window was UNTRUSTED
 *      = log2(1 + outside_W(c) + gamma*burst_W(c))    -- when flooding(c), #365 §4.1a
 * R(c) = 2^(-age(lastSeen(c)) / tau)
 * M(c) = 1                                            -- estimator retained, ordering input NOT
 *        clamp(medMTTR(c) / medMTTR(fleet), 0.5, 2)   -- still MEASURED, shipped as factors.mttr
 * S(c) = max(1 - p_heal(c)*w(c), floor)               -- neutral 1 with no R2 lane
 *        w(c) = 2^(-t_heal(c) / tau_heal)             -- 0 (neutral S) with no t_heal
 * </pre>
 *
 * <p><b>Why {@code M} does not multiply into the score</b> (#399, epic #398, ALARM-COST-MODEL.md
 * §17). {@code medMTTR} is {@code ended_at − started_at} over CLOSED episodes, and read from
 * source those two stamps are not the ends of a service time: {@code IncidentLedgerService} opens
 * the episode at the sampler's FIRST SIGHTING of the class, and the only thing that ever closes
 * one is the operator's S3 resolve verb ({@code IncidentLifecycleService.resolve}). So the
 * statistic spans first-sighting → operator-resolve and therefore CONTAINS the operator's queue
 * wait — the very quantity this ordering controls. That makes it ENDOGENOUS: rank a class low, it
 * waits longer, its measured MTTR rises, {@code M} rises, it ranks higher next cycle. Under the
 * shipped multiply that loop is negative/self-correcting (which is why nothing was on fire), but
 * it means {@code M} was substantially measuring THE QUEUE rather than the class. Inverting it to
 * {@code 1/M} — the Smith's-rule reading an adversarial harm search appeared to recommend
 * (docs/reviews/R-ATTENTION-HARM-2026-08.md) — flips the same loop POSITIVE: rank low ⇒ longer TTR
 * ⇒ smaller {@code 1/M} ⇒ rank lower still, i.e. a starvation mechanism. Both readings are
 * therefore refused and {@code M ≡ 1}, on grounds independent of that report's invented
 * distributions. The precedent is in this class already: {@code eff(c)} is excluded from the v1
 * score for an analogous honesty problem (§3.1). {@code M} re-enters the ordering only behind an
 * UNCONTAMINATED estimator (measured from first-operator-touch, or with the queue wait
 * subtracted); the recorded falsifier is that if median time-to-touch turns out to be &lt; 5 % of
 * median episode duration, the endogeneity is noise and the Smith reading becomes live again.
 *
 * <p><b>{@code mttrFactor()} stays, and {@code factors.mttr} keeps reporting the real clamped
 * ratio</b> — deliberately, not by omission. The estimator, the {@code min-closed-episodes} floor
 * and the {@code mttr-clamp-{low,high}} knobs are all retained so the re-entry above is a
 * one-line change rather than an archaeology exercise, and the ratio is honest EVIDENCE about the
 * class that the rationale's own resolve-time clause already quotes the median of. Reporting a
 * hardcoded {@code 1.0} there would have hidden a measurement the operator can read, to describe
 * an ordering rule that is documented instead. The one consumer that DID tell the operator this
 * factor moved the card — the {@code AttentionBadge} glossary tooltip's "weighted by this class's
 * historic time-to-resolve" — is corrected in the same change; no other consumer of
 * {@code AttentionFactors.mttr()} makes an ordering claim.
 *
 * <p><b>The neutrality property this whole slice rests on.</b> Every discriminating factor
 * degrades to a MULTIPLICATIVE IDENTITY when its evidence is missing: no R2 lane ⇒ S = 1, no
 * trustworthy arrival sample ⇒ F = 1 (and since #399 M is identically 1, evidence or not). The ONE deliberate exception is a
 * class with no ledger row (or a window whose samples were all trustworthy and all flat): there
 * F = 0 and the score is 0 for EVERYONE, which is not a demotion but the whole-fleet collapse to
 * the count-only tie-break — the design's stated guarantee. "No evidence at all" and "evidence we
 * were not allowed to trust" are different claims and get different answers: the first is uniform
 * across the fleet and harms nobody, the second correlates with class SIZE and demoted exactly
 * the largest classes until this review fixed it. So with an empty ledger every class scores
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
 * This is the "adapt the join, never re-derive the statistic" contract of §4.2.
 *
 * <p><b>The 0.25 floor is INERT, and the "at most 4x" guarantee comes from somewhere else</b>
 * (#400 part A, R-ATTENTION-HARM-2026-08 §6). {@code SELF_HEAL_LIKELY} maps to
 * {@code P_HEAL_LIKELY = 0.75}, so the most demoting value {@code S} can take is
 * {@code 1 - 0.75 = 0.25} — the default floor EXACTLY, reached from above and therefore never
 * selected. Deleting the clamp entirely was measured bit-identical across all 30 700 simulated
 * fleets. A proven self-healer IS demoted at most 4x and never zeroed, but that bound is LANE
 * QUANTISATION arithmetic, not a clamp; {@code inspector.triage.attention.self-heal-floor} is a
 * provable no-op at every value {@code <= 0.25}. The knob is kept — not deleted — because a value
 * STRICTLY ABOVE 0.25 does bind and weakens demotion (a legitimate deployment lever), and because
 * it remains the guard if {@code S} is ever fed a rate above 0.75 directly. Both halves of that
 * boundary are pinned by
 * {@code AttentionScoreCalculatorTest#theSelfHealFloorIsAProvableNoOpAtOrBelowTheLaneQuantisationMinimumAndBindsOnlyStrictlyAbove}
 * so the false attribution cannot drift back into the copy.
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
        return score(
                liveTotal,
                history,
                fleetMedianMttrSeconds,
                selfHeal,
                config,
                now,
                AttentionRationale.DeadLetterEvidence.ABSENT);
    }

    /**
     * #388: the population-aware suffix's own evidence, threaded through to
     * {@link AttentionRationale#sentence}. {@code AttentionScoreService.forClass()} never
     * supplies anything but {@link AttentionRationale.DeadLetterEvidence#ABSENT} (the six-arg
     * overload above) — only its {@code decorate()} dashboard path can derive real evidence from
     * an {@code ErrorGroup}, per the #388 design's locked build contract.
     */
    public static AttentionScore score(
            long liveTotal,
            ClassHistory history,
            Long fleetMedianMttrSeconds,
            SelfHealStats selfHeal,
            AttentionConfig config,
            Instant now,
            AttentionRationale.DeadLetterEvidence deadLetters) {
        ClassHistory evidence = history != null ? history : ClassHistory.none();

        long arrivals = Math.max(0, evidence.arrivals());
        boolean flooding = flooding(evidence, config);
        double frequency = frequency(evidence, config);

        long ageSeconds = ageSeconds(evidence.lastSeen(), now);
        double recency = recency(ageSeconds, config.recencyHalfLife());

        List<Long> closed = evidence.closedEpisodeSeconds();
        boolean mttrKnown = closed.size() >= config.minClosedEpisodes();
        Long classMedian = mttrKnown ? Quantiles.median(closed) : null;
        double mttr = mttrFactor(classMedian, fleetMedianMttrSeconds, config);

        SelfHealLane lane = AttentionRationale.laneOf(selfHeal);
        Long healSeconds = selfHeal != null ? selfHeal.ttsP50Seconds() : null;
        double selfHealFactor = selfHealFactor(lane, healSeconds, config.selfHealFloor(), config.selfHealHorizon());

        // #399: M is NOT a term here — see the class javadoc. The ratio above is computed for
        // `factors.mttr` (evidence + the retained estimator), never multiplied into the ordering.
        double score = frequency * recency * selfHealFactor;
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
                insufficient,
                evidence.arrivalsUnknown(),
                Math.max(0L, evidence.discardedArrivalSamples()),
                flooding,
                burstArrivals(evidence),
                config.burstWindow().toSeconds(),
                evidence.burstUnknown(),
                Math.max(0L, evidence.discardedBurstSamples()));
        return new AttentionScore(
                score,
                factors,
                AttentionRationale.sentence(liveTotal, factors, selfHeal, deadLetters),
                suggestedAckExpirySeconds(closed, config));
    }

    /**
     * The §3.2 ack-expiry SUGGESTION: an ack is a bet that attention is not needed for a while, so
     * the honest proxy for "how long" is the class's P75 closed-episode duration. Below the same
     * closed-episode floor the M factor uses, there is NO suggestion (today's behavior: the UI's
     * initial selection stays {@code none}). The operator always overrides and the R-BAU-01
     * resurface guarantees are untouched.
     *
     * <p><b>What "P75" means here, and the copy correction it forced (review fix).</b>
     * {@link Quantiles} is deliberately NEAREST-RANK and un-interpolated, so at the
     * {@code min-closed-episodes = 3} floor {@code ceil(0.75 · 3) = 3} ⇒ index 2 ⇒ the LONGEST of
     * the three recorded episodes. The estimator is kept — erring long is the safe direction for
     * a mute suggestion (a too-short expiry buys an interruption the operator did not ask for),
     * an interpolated quantile at n = 3 would invent precision the sample does not carry, and the
     * same estimator is shared with {@code SelfHealStatsComputer} so "P75" means one thing across
     * both research tracks. What was WRONG was the copy: "episodes of this class usually resolve
     * within X" reads as a typical-case claim, while the statistic is "at least 75 % of the N
     * recorded episodes resolved within X — and at N = 3 that is all three, i.e. the observed
     * maximum". §3.2 now says the latter, in exactly those terms.
     */
    static Long suggestedAckExpirySeconds(List<Long> closedEpisodeSeconds, AttentionConfig config) {
        if (closedEpisodeSeconds.size() < config.minClosedEpisodes()) {
            return null;
        }
        return Quantiles.percentile(closedEpisodeSeconds, 0.75);
    }

    /**
     * {@code log2(1 + arrivals)}, EXCEPT when the class's whole F window was untrustworthy — every
     * differenceable sample discarded for truncation (R-SEM-12 floor) or blindness (#302). Then
     * the honest answer is "unknown", and §4.1's degradation rule says an unknown factor reads as
     * the MULTIPLICATIVE IDENTITY, not as zero.
     *
     * <p>This is the review's confirmed defect, and the reason it is a defect rather than a
     * conservative choice: {@code F} is a FACTOR of {@code A(c) = F·R·S}, so {@code F = 0}
     * zeroes the entire score whatever R and S say. On an engine permanently at its
     * failure-lane scan cap EVERY group it touches is truncated in EVERY bucket, so every one of
     * its classes collapsed to {@code A = 0} — and truncation correlates with SIZE, so enabling
     * the flag on such a fleet systematically demoted the biggest classes below a one-member
     * class with a single arrival. Neutral-1 says "no arrival evidence here"; zero said "this
     * class is provably not growing", which the data never showed.
     *
     * <p><b>Burst-aware, behind a gate (#365, §4.1a).</b> {@code F} is a 28-day VOLUME measure and
     * {@code R} only sees {@code lastSeen} age, so two classes both "last seen now" with 100
     * arrivals scored identically whether those arrivals trickled over four weeks or all landed in
     * the last ten minutes — while the alarm-management literature is explicit that PEAK rates,
     * not average rates, are what make operators miss things (Beebe 2013; ISA-18.2 defines a flood
     * on a ten-minute peak window). Under {@link #flooding} the window's arrivals are DECOMPOSED
     * into {@code outside_W + burst_W} and the burst half is weighed at gamma. Below the onset
     * this method returns the shipped expression itself, so the amendment is provably inert
     * outside flood conditions.
     */
    static double frequency(ClassHistory evidence, AttentionConfig config) {
        if (evidence.arrivalsUnknown()) {
            return 1.0;
        }
        long arrivals = Math.max(0, evidence.arrivals());
        if (!flooding(evidence, config)) {
            // BYTE-IDENTICAL to the shipped formula — the same expression, not an approximation of
            // it. Everything §5.5's neutrality guarantee rests on lives on this one line.
            return log2(1 + arrivals);
        }
        // The PARTITION, in code: `outside` is a SUBTRACTION of the burst bin from the very same
        // sum, so the two bins cannot both bank the same delta however the SQL is later edited —
        // an arithmetic guarantee rather than a review convention (§13 F3).
        long burst = burstArrivals(evidence);
        long outside = arrivals - burst;
        return log2(1 + outside + config.burstWeight() * burst);
    }

    /**
     * The §4.1a flood gate — a STATELESS two-bin Schmitt trigger on ISA-18.2's own asymmetric
     * thresholds: entry needs a genuine onset in the current window; the hold leg keeps a decaying
     * flood up while it stays at or above the exit AND the onset sits in the PRIOR window.
     *
     * <p><b>{@code prior_W} must reach the onset ALONE.</b> Summing the two bins would open a
     * back-door entry — 6 arrivals now plus 6 arrivals in the previous window is 12 across TWENTY
     * minutes, which never was a ten-minute flood and must not gate as one.
     *
     * <p><b>Untrusted evidence can only ever close this gate, never open it.</b> A burst bin with
     * samples but not one trustworthy is UNKNOWN, and so is a wholly untrusted 28-day window (of
     * which the bin is a subset). Both force the gate off, leaving {@code F} at its shipped value:
     * the amendment can suppress a promotion it cannot justify, but it can never demote a class
     * below where the shipped formula put it, which is strictly safer than the §13 F2 defect class
     * this discipline is inherited from.
     */
    static boolean flooding(ClassHistory evidence, AttentionConfig config) {
        if (evidence.arrivalsUnknown() || evidence.burstUnknown()) {
            return false;
        }
        long burst = burstArrivals(evidence);
        long prior = Math.max(0L, evidence.priorBurstArrivals());
        return burst >= config.burstOnset() || (burst >= config.burstExit() && prior >= config.burstOnset());
    }

    /**
     * The burst bin, clamped into its own window's total. {@code burst_arrivals <= arrivals} holds
     * in SQL by construction (identical filters over a strictly narrower time range, asserted on
     * every fixture in {@code LedgerNativeQueriesIT}); the clamp is the arithmetic backstop that
     * keeps {@code outside = arrivals − burst} from ever going NEGATIVE if that ever stopped being
     * true — a negative outside term would be double-banking with the sign flipped.
     */
    private static long burstArrivals(ClassHistory evidence) {
        long arrivals = Math.max(0L, evidence.arrivals());
        return Math.min(Math.max(0L, evidence.burstArrivals()), arrivals);
    }

    /** {@code 2^(-age/tau)} — 1 at age 0, 0.5 at one half-life. A null/future lastSeen reads 1. */
    static double recency(long ageSeconds, Duration halfLife) {
        long tau = Math.max(1, halfLife.toSeconds());
        if (ageSeconds <= 0) {
            return 1.0;
        }
        return Math.pow(2.0, -((double) ageSeconds / tau));
    }

    /**
     * The retained M ESTIMATOR — a diagnostic surfaced on {@code factors.mttr}, NOT a term of the
     * v1 score (#399, §17; the class javadoc carries the endogeneity argument). Neutral 1 whenever
     * either side of the ratio is unknown or degenerate — never a guess. Kept computing, with its
     * clamp knobs, so re-entry behind an uncontaminated estimator is a one-line change.
     */
    static double mttrFactor(Long classMedianSeconds, Long fleetMedianSeconds, AttentionConfig config) {
        if (classMedianSeconds == null || fleetMedianSeconds == null || fleetMedianSeconds <= 0) {
            return 1.0;
        }
        double ratio = (double) classMedianSeconds / fleetMedianSeconds;
        return Math.max(config.mttrClampLow(), Math.min(config.mttrClampHigh(), ratio));
    }

    /**
     * {@code max(floor, 1 - p_heal * w(t_heal))} — the §4.1b amendment (#400 part B). An absent
     * or insufficient lane is neutral 1, exactly as before.
     *
     * <p><b>The defect this closes.</b> {@code S} consumed the self-heal PROBABILITY and never its
     * TIMING, so a class that reliably heals *eventually* was demoted precisely as hard as one
     * that heals *in a minute*. Measured (R-ATTENTION-HARM-2026-08 §4c, the pre-registered
     * {@code t_heal / mttr} covariate): while the heal lands within ~4 service times the demotion
     * is a large win, but past ~8 service times deferring the class buys nothing and costs
     * everything — <b>80.73 % harm, 0.00 % help</b>, with count-only becoming outright optimal.
     * The evidence needed to tell the two apart was already on the wire: {@code
     * SelfHealStats.ttsP50Seconds}, the median observed duration of this class's SELF_HEALED
     * spells (RETRYING-RISK-LANE.md §3.2), needing no new query and no new R2 work.
     *
     * <p><b>Two properties that make the amendment safe by construction</b>, in the §4.1a
     * discipline (an amendment must be provably inert where the shipped behaviour was right):
     * <ol>
     *   <li>{@code w ∈ [0, 1]} ⇒ {@code S ∈ [1 - p_heal, 1]}. The timing term can only ever
     *       WEAKEN a demotion, never deepen one — no class can be pushed below where the shipped
     *       formula put it, whatever {@code t_heal} says.</li>
     *   <li>At {@code t_heal = 0}, {@code w = 2^0 = 1} and {@code S} is BYTE-IDENTICAL to the
     *       shipped expression. That is not an edge case: the spell resolution floor is one
     *       sampler bucket (§3.1), so every class healing inside 60 s reports {@code
     *       ttsP50Seconds = 0} — i.e. the amendment is exactly inert across the whole regime the
     *       harm search says {@code S} genuinely wins in.</li>
     * </ol>
     *
     * <p><b>Absent timing degrades to the multiplicative identity, and why that is the §4.1
     * answer.</b> {@code tts*} is absent whenever {@code healed = 0} or the raw sample sits below
     * the {@code n < 10} floor, while the DISPLAYED lane can still be a dwelled risk lane at that
     * moment (it is stabilized over ten cycles; the raw statistic is not), so "lane known, timing
     * unknown" is reachable — a {@code SELF_HEAL_UNLIKELY} class with zero healed spells is the
     * routine case. The rejected alternative was {@code w = 1} ("preserve today's behaviour"),
     * and it is rejected for the same reason §13 F2 was a defect and not a conservative choice:
     * it would assert <i>this class heals immediately</i> — the single most demoting value
     * available — on no evidence whatsoever. §4.1's degradation rule says an unknown factor reads
     * as the MULTIPLICATIVE IDENTITY, so an unknown {@code w} declines to demote at all and
     * {@code S = 1}. That is also the safe direction here: it can only PROMOTE a class toward
     * attention, never bury one, which is the same never-hide doctrine the rest of the model
     * fails toward.
     *
     * <p><b>Stabilization contract (§4.1), honestly stated.</b> {@code p_heal} still comes from
     * the server-dwelled lane, so the discrete part of {@code S} is unchanged and still cannot
     * flap while the badge holds. {@code ttsP50Seconds} is <b>not</b> dwell-stabilized — {@code
     * SelfHealStatsService} runs only the LANE through {@code DwellStateMachine} and pairs it with
     * the raw per-cycle statistic — and this class is the first consumer for which that matters,
     * so it is named rather than papered over. It is admissible here because the contract exists
     * to prevent DISCONTINUOUS reordering: the lane is a classification, so an epsilon move in the
     * underlying rate flips {@code p_heal} 0.75 → 0.50 and jerks the ordering while the badge says
     * nothing changed. {@code w} has no threshold to cross — it is continuous and monotone in
     * {@code t_heal}, bounding the response at {@code |ΔS| <= p_heal · ln2/tau_heal · Δt} (at the
     * PT1H default, a whole sampler bucket of movement in the median moves {@code S} by under
     * 0.009). It is additionally NOT a per-cycle rate but a 90-day order statistic over at least
     * ten completed spells, so moving it one bucket requires a whole spell to enter or leave the
     * window AND to cross the middle of the distribution. If a future estimator ever makes the
     * duration jumpy, the fix is to dwell it in {@code SelfHealStatsService} next to the lane —
     * never to re-derive or smooth it here (§4.2: adapt the join, never re-derive the statistic).
     *
     * @param ttsP50Seconds {@code SelfHealStats.ttsP50Seconds} — the class's MEDIAN observed
     *     self-heal duration, or {@code null} when no duration distribution exists
     * @param floor {@code inspector.triage.attention.self-heal-floor}; inert at every value
     *     {@code <= 0.25} (see this class's own doc comment)
     * @param horizon tau_heal, {@code inspector.triage.attention.self-heal-horizon}
     */
    static double selfHealFactor(SelfHealLane lane, Long ttsP50Seconds, double floor, Duration horizon) {
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
        return Math.max(floor, 1.0 - pHeal * healTimeliness(ttsP50Seconds, horizon));
    }

    /**
     * {@code w = 2^(-t_heal / tau_heal)} — 1 for a heal inside one sampler bucket, 0.5 at the
     * horizon, ~0 far beyond it. Deliberately the SAME half-life family as {@link #recency}: one
     * decay shape in the model means one operator mental model ("half-life"), and a continuous
     * response is what lets a non-dwelled input in at all (see {@link #selfHealFactor}).
     *
     * <p><b>Unknown reads 0, not 1</b> — {@code S} then collapses to the multiplicative identity
     * per §4.1's degradation rule, rather than asserting an instant heal nobody measured.
     */
    static double healTimeliness(Long ttsP50Seconds, Duration horizon) {
        if (ttsP50Seconds == null || ttsP50Seconds < 0) {
            return 0.0;
        }
        long tau = Math.max(1, horizon.toSeconds());
        return Math.pow(2.0, -((double) ttsP50Seconds / tau));
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

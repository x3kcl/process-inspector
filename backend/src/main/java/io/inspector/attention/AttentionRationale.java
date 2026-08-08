package io.inspector.attention;

import io.inspector.dto.AttentionFactors;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.SelfHealStats;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.selfheal.SelfHealLane;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ONE glanceable sentence rationale (ALARM-COST-MODEL.md §4.3 — a hard requirement from
 * issue #348: "a score no rationale can explain is a rejected design by construction"). Rendered
 * as visible page text on the card face since §12.1/issue #374 — previously reachable only via a
 * hover {@code title} tooltip.
 *
 * <p>Computed SERVER-side so every consumer renders the identical sentence — the same doctrine
 * that put the R2 dwell state machine on the server (RETRYING-RISK-LANE §4.2 rule 3): two
 * operators must never see two different explanations of the same ordering.
 *
 * <p>Shape — four clauses, {@code ·}-separated, one line, always terminated:
 *
 * <pre>
 * 21 failing · last seen 2 min ago · typically takes 4 h to resolve · no self-heal history.
 * </pre>
 *
 * <p>A FIFTH clause appears only when the F factor's window was wholly untrusted (review fix):
 *
 * <pre>
 * 4000 failing · last seen just now · … · arrival volume unknown (scan truncated or engine
 * unreachable all window).
 * </pre>
 *
 * <p>...and the #365 burst clause only when the §4.1a flood gate actually fired — the absolute
 * count and the window, because a bare ratio ("spiking 4x") does not tell an operator whether to
 * act now. When the burst bin was UNKNOWN instead, the clause is "recent arrival rate unknown":
 * never "not spiking", which the evidence cannot support.
 *
 * <pre>
 * 120 failing · last seen just now · … · spiking: 40 in the last 10 min.
 * </pre>
 *
 * <p>Every clause states EVIDENCE, never a verdict, and an estimate under its own sample-size
 * floor says "no history" instead of a number (§6 honesty rails). Nothing here prescribes an
 * intervention — this track orders attention only (§9 non-goal; issue #106 stays untouched).
 */
public final class AttentionRationale {

    private AttentionRationale() {}

    /**
     * #388: the population-aware suffix's own evidence — the DL/retrying split PLUS whether it
     * can be trusted enough to render. {@link #ABSENT} is the honest "no evidence supplied"
     * state {@code AttentionScoreService.forClass()} passes ALWAYS (it has no {@link ErrorGroup}
     * to derive counts from — the Incident Ledger detail/list entry point never gets the
     * suffix, recorded as a known limitation in RETRYING-RISK-LANE.md §4.2); only
     * {@code AttentionScoreService.decorate()} (the Stage 0 dashboard path) can ever supply
     * anything else.
     *
     * <p><b>Trust rule (verbatim from the #388 design review, fold-in F5):</b> the split is
     * trusted only when BOTH {@code deadLetterCount} and {@code retryingCount} are non-null (the
     * {@code TriageScopeProjector}'s partial-scope null must never be papered over — a fleet-wide
     * N must never leak to a scoped viewer) AND every engine the group touches
     * ({@code countsByEngine}'s keys) reports {@code ok} with an UNTRUNCATED failure-lane scan.
     */
    public record DeadLetterEvidence(Long deadLetterCount, Long retryingCount, boolean countsTrusted) {

        /** No evidence supplied — the suffix can never render (base clause, always). */
        public static final DeadLetterEvidence ABSENT = new DeadLetterEvidence(null, null, false);

        /**
         * Derives the evidence from a live {@link ErrorGroup} plus the dashboard's per-engine
         * honesty envelopes — the ONLY place this composition happens, so
         * {@code AttentionScoreService.decorate()} never re-derives the trust rule itself.
         */
        public static DeadLetterEvidence of(ErrorGroup group, Map<String, PerEngineTriage> perEngine) {
            if (group == null) {
                return ABSENT;
            }
            Long deadLetterCount = group.deadLetterCount();
            Long retryingCount = group.retryingCount();
            if (deadLetterCount == null || retryingCount == null) {
                return new DeadLetterEvidence(deadLetterCount, retryingCount, false);
            }
            return new DeadLetterEvidence(deadLetterCount, retryingCount, everyTouchedEngineTrusted(group, perEngine));
        }

        /**
         * #388 fold-in 3 (DELIBERATE, verbatim from the design review): this reuses the
         * CONFLATED {@code dlqScan} marker — the same one that OR-conflates all three failure
         * lanes (timer, executable, deadletter) — rather than the narrower
         * {@code deadletterTruncated} flag. A timer/executable-only truncation says nothing
         * about whether the DEADLETTER count itself is trustworthy on its own, but
         * {@code retryingCount} depends on exactly those two other lanes, so suppressing the
         * suffix on ANY lane truncation is the conservative, fail-toward-the-weaker-claim
         * choice — the same direction as every other honesty rail in this class. Do NOT "fix"
         * this to {@code deadletterTruncated} alone without re-checking {@code retryingCount}'s
         * own trust needs against the timer/executable lanes first.
         */
        private static boolean everyTouchedEngineTrusted(ErrorGroup group, Map<String, PerEngineTriage> perEngine) {
            Map<String, Map<String, Long>> countsByEngine = group.countsByEngine();
            if (countsByEngine == null || countsByEngine.isEmpty()) {
                return false; // nothing to verify trust against — fail toward the weaker claim
            }
            for (String engineId : countsByEngine.keySet()) {
                PerEngineTriage envelope = perEngine != null ? perEngine.get(engineId) : null;
                if (envelope == null || !envelope.ok() || !"complete".equals(envelope.dlqScan())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Convenience overload for callers with no dead-letter split evidence (base clause, always). */
    public static String sentence(long liveTotal, AttentionFactors factors, SelfHealStats selfHeal) {
        return sentence(liveTotal, factors, selfHeal, DeadLetterEvidence.ABSENT);
    }

    /**
     * @param liveTotal the class's rendered member count (a lower bound when its scan truncated —
     *     the card's existing R-SEM-12 badge already says so; the sentence never re-asserts
     *     completeness)
     * @param factors the score's own factor block — the sentence is composed from the EXACT
     *     numbers that produced the score (age, the sub-floor-aware median, the two arrival
     *     honesty rails and the #365 burst gate), never from a second derivation that could drift
     *     away from them
     * @param selfHeal the R2 statistic, or {@code null} when none applied
     * @param deadLetters #388's population-aware suffix evidence — {@link DeadLetterEvidence#ABSENT}
     *     renders the base {@code SELF_HEAL_LIKELY} clause unchanged, always
     */
    public static String sentence(
            long liveTotal, AttentionFactors factors, SelfHealStats selfHeal, DeadLetterEvidence deadLetters) {
        long ageSeconds = factors.ageSeconds();
        List<String> clauses = new ArrayList<>(6);
        clauses.add(liveTotal + " failing");
        clauses.add(ageSeconds < 60 ? "last seen just now" : "last seen " + humanize(ageSeconds) + " ago");
        clauses.add(
                factors.medianMttrSeconds() != null
                        ? "typically takes " + humanize(factors.medianMttrSeconds()) + " to resolve"
                        : "no resolve-time history");
        clauses.add(selfHealClause(selfHeal, deadLetters));
        if (factors.flooding()) {
            // #365: the ABSOLUTE count and the window, never a bare ratio — "spiking 4x" tells an
            // operator nothing about whether to walk over to the console now.
            clauses.add("spiking: " + factors.burstArrivals() + " in the last "
                    + Math.max(1L, factors.burstWindowSeconds() / 60L) + " min");
        }
        if (factors.arrivalsUnknown()) {
            clauses.add("arrival volume unknown (scan truncated or engine unreachable all window)");
        } else if (factors.burstUnknown()) {
            // Only when the WIDER window was measurable: "arrival volume unknown" already implies
            // the recent rate is too, and one sentence must not say the same thing twice.
            clauses.add("recent arrival rate unknown");
        }
        return String.join(" · ", clauses) + ".";
    }

    private static String selfHealClause(SelfHealStats selfHeal, DeadLetterEvidence deadLetters) {
        SelfHealLane lane = laneOf(selfHeal);
        if (lane == null || lane == SelfHealLane.INSUFFICIENT_HISTORY) {
            return "no self-heal history";
        }
        String record = " (" + selfHeal.healed() + "/" + selfHeal.n() + ")";
        return switch (lane) {
            // #387: this clause used to stop at the bare "(H/N)" fraction, which tester T3 read as
            // "H of the N *currently failing* instances" — it is a lifetime historical spell count.
            // The badge on /incidents (frontend/src/incidents/selfHeal.ts, same lane) already names
            // the unit ("past spells") and, when the server has it, the timing half ("typically ≤ X
            // min", sourced from the SAME ttsP90Seconds already served — never a second derivation,
            // per this class's own doc comment). Mirror that format exactly here so the two surfaces
            // read identically; AttentionRationaleTest + selfHeal.test.ts both lock this string so
            // a future edit to either side goes red before the surfaces can drift apart again.
            //
            // #388: T4 (ALARM-COST-MODEL.md §8.9 finding 2) correctly read a LIKELY class showing
            // `DLQ 25 / retrying 0` as "these won't self-heal further without action" — the badge's
            // "usually self-heals" implies a passive "leave it" that only ever applied to members
            // CURRENTLY in a retrying spell. `populationSuffix` appends the locked, state-exact
            // qualifier ONLY to this lane (T4's finding is specifically about LIKELY's implied
            // posture) — never a verdict, purely the live standing-dead-letter count next to the
            // historic rate it cannot speak for.
            case SELF_HEAL_LIKELY -> "usually self-heals" + likelyRecord(selfHeal) + populationSuffix(deadLetters);
            case SELF_HEAL_MIXED -> "mixed self-heal record" + record;
            case SELF_HEAL_UNLIKELY -> "rarely self-heals" + record;
            case INSUFFICIENT_HISTORY -> "no self-heal history";
        };
    }

    /**
     * #388 (design LOCKED v2.1): appended AFTER the base clause's closing {@code )}, one code
     * path, never a second string. Trigger is {@code deadLetterCount > 0} — independent of
     * {@code retryingCount}'s value (RETRYING-RISK-LANE.md §3.1: a live spell alongside standing
     * dead-letters is routine, so keying on {@code retrying == 0} was the rejected v1 shape,
     * REQUEST-CHANGES finding 3) — gated on {@link DeadLetterEvidence#countsTrusted()} so an
     * untrusted/partial-scope split NEVER renders it (fail toward the base clause, always the
     * weaker claim). "Won't … without action" is not a forward-looking verdict: a dead-lettered
     * job has structurally exhausted its retries at read time — evidence, not a promise about
     * what an operator or a later engine-side mutation does next.
     */
    private static String populationSuffix(DeadLetterEvidence deadLetters) {
        if (deadLetters == null || !deadLetters.countsTrusted()) {
            return "";
        }
        Long deadLetterCount = deadLetters.deadLetterCount();
        if (deadLetterCount == null || deadLetterCount <= 0) {
            return "";
        }
        return " — not the " + deadLetterCount + " dead-lettered (no retries left)";
    }

    /**
     * "(H/N past spells)", plus ", typically ≤ X min" when {@code ttsP90Seconds} is present — the
     * SELF_HEAL_LIKELY badge's own clause (RETRYING-RISK-LANE.md §4.1), never a fabricated number
     * when the server has none (RETRYING-RISK-LANE.md §5 honesty rails).
     */
    private static String likelyRecord(SelfHealStats selfHeal) {
        Long ttsP90 = selfHeal.ttsP90Seconds();
        String typical = ttsP90 != null ? ", typically ≤ " + minutesCeil(ttsP90) + " min" : "";
        return " (" + selfHeal.healed() + "/" + selfHeal.n() + " past spells" + typical + ")";
    }

    /**
     * Ceiling minutes, mirroring frontend/src/incidents/selfHeal.ts's {@code minutesCeil} exactly:
     * a "≤" bound must still hold after rounding, so this rounds UP, never nearest/down
     * (RETRYING-RISK-LANE.md §3.1). Never below 1 minute.
     */
    static long minutesCeil(long seconds) {
        return Math.max(1L, (seconds + 59) / 60);
    }

    /** The displayed (server-dwelled) lane, or {@code null} when absent/unparseable. */
    static SelfHealLane laneOf(SelfHealStats selfHeal) {
        if (selfHeal == null || selfHeal.lane() == null) {
            return null;
        }
        try {
            return SelfHealLane.valueOf(selfHeal.lane());
        } catch (IllegalArgumentException e) {
            return null; // an unknown lane string reads as "no history", never as a risk claim
        }
    }

    /**
     * Legible magnitude, largest unit that reaches 1 — "45 s", "2 min", "1.5 h", "3 d". One
     * decimal below 10 (so "1.5 h" is not rounded into a lie), whole numbers above it.
     */
    static String humanize(long seconds) {
        long absolute = Math.max(0, seconds);
        if (absolute < 60) {
            return absolute + " s";
        }
        if (absolute < 3600) {
            return format(absolute / 60.0) + " min";
        }
        if (absolute < 86_400) {
            return format(absolute / 3600.0) + " h";
        }
        return format(absolute / 86_400.0) + " d";
    }

    private static String format(double value) {
        if (value >= 10) {
            return String.valueOf(Math.round(value));
        }
        String rendered = String.format(Locale.ROOT, "%.1f", value);
        return rendered.endsWith(".0") ? rendered.substring(0, rendered.length() - 2) : rendered;
    }
}

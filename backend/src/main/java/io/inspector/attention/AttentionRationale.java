package io.inspector.attention;

import io.inspector.dto.AttentionFactors;
import io.inspector.dto.SelfHealStats;
import io.inspector.selfheal.SelfHealLane;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * @param liveTotal the class's rendered member count (a lower bound when its scan truncated —
     *     the card's existing R-SEM-12 badge already says so; the sentence never re-asserts
     *     completeness)
     * @param factors the score's own factor block — the sentence is composed from the EXACT
     *     numbers that produced the score (age, the sub-floor-aware median, the two arrival
     *     honesty rails and the #365 burst gate), never from a second derivation that could drift
     *     away from them
     * @param selfHeal the R2 statistic, or {@code null} when none applied
     */
    public static String sentence(long liveTotal, AttentionFactors factors, SelfHealStats selfHeal) {
        long ageSeconds = factors.ageSeconds();
        List<String> clauses = new ArrayList<>(6);
        clauses.add(liveTotal + " failing");
        clauses.add(ageSeconds < 60 ? "last seen just now" : "last seen " + humanize(ageSeconds) + " ago");
        clauses.add(
                factors.medianMttrSeconds() != null
                        ? "typically takes " + humanize(factors.medianMttrSeconds()) + " to resolve"
                        : "no resolve-time history");
        clauses.add(selfHealClause(selfHeal));
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

    private static String selfHealClause(SelfHealStats selfHeal) {
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
            case SELF_HEAL_LIKELY -> "usually self-heals" + likelyRecord(selfHeal);
            case SELF_HEAL_MIXED -> "mixed self-heal record" + record;
            case SELF_HEAL_UNLIKELY -> "rarely self-heals" + record;
            case INSUFFICIENT_HISTORY -> "no self-heal history";
        };
    }

    /**
     * "(H/N past spells)", plus ", typically ≤ X min" when {@code ttsP90Seconds} is present — the
     * SELF_HEAL_LIKELY badge's own clause (RETRYING-RISK-LANE.md §4.1), never a fabricated number
     * when the server has none (§6 honesty rails).
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

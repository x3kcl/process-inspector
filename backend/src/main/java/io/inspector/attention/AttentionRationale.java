package io.inspector.attention;

import io.inspector.dto.SelfHealStats;
import io.inspector.selfheal.SelfHealLane;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ONE-SENTENCE, single-tooltip rationale (ALARM-COST-MODEL.md §4.3 — a hard requirement from
 * issue #348: "a score no tooltip can explain is a rejected design by construction").
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
     * @param ageSeconds age of {@code lastSeen}; negative/zero reads as "just now"
     * @param medianMttrSeconds {@code null} below the closed-episode floor ⇒ "no resolve-time
     *     history"
     * @param selfHeal the R2 statistic, or {@code null} when none applied
     */
    public static String sentence(long liveTotal, long ageSeconds, Long medianMttrSeconds, SelfHealStats selfHeal) {
        List<String> clauses = new ArrayList<>(4);
        clauses.add(liveTotal + " failing");
        clauses.add(ageSeconds < 60 ? "last seen just now" : "last seen " + humanize(ageSeconds) + " ago");
        clauses.add(
                medianMttrSeconds != null
                        ? "typically takes " + humanize(medianMttrSeconds) + " to resolve"
                        : "no resolve-time history");
        clauses.add(selfHealClause(selfHeal));
        return String.join(" · ", clauses) + ".";
    }

    private static String selfHealClause(SelfHealStats selfHeal) {
        SelfHealLane lane = laneOf(selfHeal);
        if (lane == null || lane == SelfHealLane.INSUFFICIENT_HISTORY) {
            return "no self-heal history";
        }
        String record = " (" + selfHeal.healed() + "/" + selfHeal.n() + ")";
        return switch (lane) {
            case SELF_HEAL_LIKELY -> "usually self-heals" + record;
            case SELF_HEAL_MIXED -> "mixed self-heal record" + record;
            case SELF_HEAL_UNLIKELY -> "rarely self-heals" + record;
            case INSUFFICIENT_HISTORY -> "no self-heal history";
        };
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

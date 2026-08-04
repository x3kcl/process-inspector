package io.inspector.selfheal;

import java.util.List;

/**
 * The whole v1 "model" (RETRYING-RISK-LANE.md §3.2/§3.4): a per-class Bernoulli self-heal rate
 * with a Wilson interval, PLUS the p50/p90 time-to-self-heal distribution — descriptive
 * statistics, not ML (the design's literature-backed non-goal, §3.4/§9). Pure: zero Spring,
 * rung-1 testable over synthetic {@link RetrySpell} fixtures.
 */
public final class SelfHealStatsComputer {

    private SelfHealStatsComputer() {}

    public static RawSelfHealStats compute(List<RetrySpell> spells, int floor) {
        int n = 0;
        int healed = 0;
        int excluded = 0;
        boolean truncationTainted = false;
        List<Long> healedDurationsSeconds = new java.util.ArrayList<>();
        for (RetrySpell spell : spells) {
            if (spell.live()) {
                continue; // completed-evidence-only (§4.2 rule 1) — a live spell never counts
            }
            if (spell.excluded()) {
                excluded++;
                if (spell.truncationTainted()) {
                    truncationTainted = true;
                }
                continue;
            }
            if (!spell.countable()) {
                continue; // defensive: every non-live, non-excluded spell should be countable
            }
            n++;
            if (spell.outcome() == RetrySpell.Outcome.SELF_HEALED) {
                healed++;
                healedDurationsSeconds.add(spell.duration().toSeconds());
            }
        }

        Double wilsonLow = null;
        Double wilsonHigh = null;
        SelfHealLane rawLane;
        if (n < floor) {
            rawLane = SelfHealLane.INSUFFICIENT_HISTORY;
        } else {
            WilsonInterval.Bounds bounds = WilsonInterval.score(healed, n);
            wilsonLow = bounds.low();
            wilsonHigh = bounds.high();
            rawLane = enterLane(wilsonLow, wilsonHigh);
        }

        Long p50 = null;
        Long p90 = null;
        if (!healedDurationsSeconds.isEmpty()) {
            List<Long> sorted = healedDurationsSeconds.stream().sorted().toList();
            p50 = percentile(sorted, 0.50);
            p90 = percentile(sorted, 0.90);
        }

        return new RawSelfHealStats(n, healed, wilsonLow, wilsonHigh, p50, p90, excluded, truncationTainted, rawLane);
    }

    /**
     * The §4.1 enter-threshold classification: LIKELY at Wilson LB ≥ 0.70, UNLIKELY at Wilson
     * UB ≤ 0.30, MIXED otherwise. Used both for a class's very first raw lane and — inside
     * {@code DwellStateMachine} — as the fresh reclassification once a displayed LIKELY/
     * UNLIKELY lane's OWN (looser) exit band has actually been left.
     */
    static SelfHealLane enterLane(double wilsonLow, double wilsonHigh) {
        if (wilsonLow >= 0.70) {
            return SelfHealLane.SELF_HEAL_LIKELY;
        }
        if (wilsonHigh <= 0.30) {
            return SelfHealLane.SELF_HEAL_UNLIKELY;
        }
        return SelfHealLane.SELF_HEAL_MIXED;
    }

    private static long percentile(List<Long> sortedAscending, double p) {
        int idx = (int) Math.ceil(p * sortedAscending.size()) - 1;
        idx = Math.max(0, Math.min(idx, sortedAscending.size() - 1));
        return sortedAscending.get(idx);
    }
}

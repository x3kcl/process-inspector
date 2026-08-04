package io.inspector.selfheal;

/**
 * The §4 stability rules as a pure state transition (RETRYING-RISK-LANE.md §4.2) — zero
 * Spring, rung-1 testable with synthetic {@code (state, n, wilsonLow/High, cycleComplete,
 * liveSpell)} sequences; no real self-heal data is needed to prove this machine (none exists
 * yet — docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md).
 *
 * <p>Rules implemented, referencing §4.2's numbering:
 * <ol>
 *   <li><b>Completed-evidence-only</b> is the CALLER's job (spells feeding {@code n}/Wilson are
 *       already completed-only, {@link SelfHealStatsComputer}) — this machine only ever sees a
 *       statistic already derived that way.</li>
 *   <li><b>Hysteresis (Schmitt trigger).</b> A DISPLAYED {@code SELF_HEAL_LIKELY}/{@code
 *       SELF_HEAL_UNLIKELY} lane holds through its own (looser) EXIT band — LB &lt; 0.60 /
 *       UB &gt; 0.40 — rather than merely failing to still clear its (stricter) 0.70/0.30
 *       ENTER threshold. A bare enum "raw lane" cannot express that asymmetry, so this machine
 *       re-derives the candidate from the CURRENT displayed lane's own exit rule, falling back
 *       to the enter-threshold classification only once that exit rule has actually fired.</li>
 *   <li><b>Minimum dwell — SERVER-side.</b> A candidate lane only becomes the displayed one
 *       after holding for {@code dwellCycles} consecutive COMPLETE sampler cycles. An
 *       INCOMPLETE cycle (mirroring the regression gate's "observed" doctrine, #302) neither
 *       advances nor resets the counter — "we didn't get to look" is not evidence either way,
 *       so the state simply passes through unchanged. That check is the FIRST thing
 *       {@link #advance} does, deliberately: it must gate the RESET branch as well as the
 *       advance branch, or a flapping engine silently freezes every class's lane forever.</li>
 *   <li><b>Mid-spell monotonicity.</b> While a live spell is open, a risk-DECREASING candidate
 *       (order {@code LIKELY < MIXED < UNLIKELY}) is suppressed outright — it never even starts
 *       a dwell — except transitions into/out of {@code INSUFFICIENT_HISTORY}, which sit
 *       OUTSIDE the risk order by design and are governed purely by the floor + dwell.</li>
 * </ol>
 */
public final class DwellStateMachine {

    private DwellStateMachine() {}

    public static DwellState advance(
            DwellState state,
            int n,
            Double wilsonLow,
            Double wilsonHigh,
            int floor,
            boolean liveSpellPresent,
            boolean cycleComplete,
            int dwellCycles) {
        if (!cycleComplete) {
            // Rule 3, FIRST: an incomplete cycle contributes nothing either way — it neither
            // advances NOR resets the counter. This has to gate the whole transition, not just
            // the advance: the statistic recomputed from a blind cycle can land back on the
            // displayed lane (an unreachable engine's spells simply vanish from the window),
            // and letting that reach the "stable ⇒ any pending dwell resets" branch below means
            // a class flapping more often than every dwellCycles beats NEVER commits a lane —
            // permanently frozen on data the machine was explicitly told not to trust.
            return state;
        }
        SelfHealLane candidate = candidateLane(state.displayedLane(), n, floor, wilsonLow, wilsonHigh);
        if (liveSpellPresent && isRiskDecreasing(state.displayedLane(), candidate)) {
            candidate = state.displayedLane(); // rule 4: hold, never even start a dwell
        }
        if (candidate == state.displayedLane()) {
            return new DwellState(state.displayedLane(), null, 0); // stable: any pending dwell resets
        }
        int cycles = candidate == state.pendingLane() ? state.pendingCycles() + 1 : 1;
        if (cycles >= dwellCycles) {
            return new DwellState(candidate, null, 0); // committed
        }
        return new DwellState(state.displayedLane(), candidate, cycles);
    }

    private static SelfHealLane candidateLane(
            SelfHealLane displayed, int n, int floor, Double wilsonLow, Double wilsonHigh) {
        if (n < floor) {
            return SelfHealLane.INSUFFICIENT_HISTORY;
        }
        // n >= floor here, so wilsonLow/wilsonHigh are non-null by SelfHealStatsComputer's contract.
        return switch (displayed) {
            case SELF_HEAL_LIKELY ->
                wilsonLow < 0.60
                        ? SelfHealStatsComputer.enterLane(wilsonLow, wilsonHigh)
                        : SelfHealLane.SELF_HEAL_LIKELY;
            case SELF_HEAL_UNLIKELY ->
                wilsonHigh > 0.40
                        ? SelfHealStatsComputer.enterLane(wilsonLow, wilsonHigh)
                        : SelfHealLane.SELF_HEAL_UNLIKELY;
            case SELF_HEAL_MIXED, INSUFFICIENT_HISTORY -> SelfHealStatsComputer.enterLane(wilsonLow, wilsonHigh);
        };
    }

    private static boolean isRiskDecreasing(SelfHealLane displayed, SelfHealLane candidate) {
        Integer displayedRisk = riskOrder(displayed);
        Integer candidateRisk = riskOrder(candidate);
        if (displayedRisk == null || candidateRisk == null) {
            return false; // INSUFFICIENT_HISTORY sits outside the risk order (rule 4) — always allowed
        }
        return candidateRisk < displayedRisk;
    }

    private static Integer riskOrder(SelfHealLane lane) {
        return switch (lane) {
            case SELF_HEAL_LIKELY -> 0;
            case SELF_HEAL_MIXED -> 1;
            case SELF_HEAL_UNLIKELY -> 2;
            case INSUFFICIENT_HISTORY -> null;
        };
    }
}

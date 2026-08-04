package io.inspector.selfheal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Rung 1: the §4.2 stability rules as a pure state machine — hysteresis, minimum dwell,
 * incomplete-cycle handling, and mid-spell monotonicity — driven entirely by synthetic
 * (state, n, Wilson bounds, cycle) sequences. This is the ONLY way these paths are testable
 * today: no real self-heal data exists to drive them end-to-end (docs/reviews/
 * R2-SELFHEAL-BASELINE-2026-08.md), and the design anticipated exactly that (panel G12).
 */
class DwellStateMachineTest {

    private static final int FLOOR = 10;
    private static final int DWELL = 10;

    @Test
    void aStableCandidateNeverStartsADwell() {
        DwellState state = DwellState.initial(); // INSUFFICIENT_HISTORY, n < floor
        DwellState next = DwellStateMachine.advance(state, 3, null, null, FLOOR, false, true, DWELL);

        assertThat(next.displayedLane()).isEqualTo(SelfHealLane.INSUFFICIENT_HISTORY);
        assertThat(next.pendingLane()).isNull();
        assertThat(next.pendingCycles()).isZero();
    }

    @Test
    void aNewCandidateRequiresDwellCyclesConsecutiveCompleteCyclesToCommit() {
        DwellState state = DwellState.initial();
        // n crosses the floor with a perfect record — raw candidate is SELF_HEAL_LIKELY.
        for (int i = 1; i < DWELL; i++) {
            state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, true, DWELL);
            assertThat(state.displayedLane())
                    .as("cycle %d must not have committed yet", i)
                    .isEqualTo(SelfHealLane.INSUFFICIENT_HISTORY);
            assertThat(state.pendingCycles()).isEqualTo(i);
        }
        state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, true, DWELL);

        assertThat(state.displayedLane()).isEqualTo(SelfHealLane.SELF_HEAL_LIKELY);
        assertThat(state.pendingCycles()).isZero();
    }

    @Test
    void anIncompleteCycleNeitherAdvancesNorResetsTheDwellCounter() {
        DwellState state = DwellState.initial();
        state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, true, DWELL);
        state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, true, DWELL);
        assertThat(state.pendingCycles()).isEqualTo(2);

        // ten incomplete cycles in a row: no progress, no reset either.
        for (int i = 0; i < 10; i++) {
            state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, false, DWELL);
        }

        assertThat(state.displayedLane()).isEqualTo(SelfHealLane.INSUFFICIENT_HISTORY);
        assertThat(state.pendingLane()).isEqualTo(SelfHealLane.SELF_HEAL_LIKELY);
        assertThat(state.pendingCycles()).isEqualTo(2);
    }

    @Test
    void anIncompleteCycleWhoseCandidateMatchesTheDisplayedLaneDoesNotResetTheDwellEither() {
        // The other incomplete-cycle test only covers the case where the candidate STAYS
        // different, which never reaches the reset branch. This is the one that matters in
        // production: on a blind cycle an unreachable engine's spells simply vanish from the
        // window, so the recomputed statistic drops back to the DISPLAYED lane — and the
        // "stable ⇒ any pending dwell resets" branch would then wipe the dwell from data the
        // machine was explicitly told not to trust. With an engine flapping more often than
        // every DWELL cycles (~10 min at the 60s beat) no class would EVER commit a lane.
        DwellState state = DwellState.initial();
        for (int i = 0; i < DWELL - 1; i++) {
            state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, true, DWELL);
        }
        assertThat(state.pendingCycles()).isEqualTo(DWELL - 1);

        // blind cycle: the class's evidence is unobservable, so n reads back below the floor and
        // the candidate is the currently displayed INSUFFICIENT_HISTORY.
        state = DwellStateMachine.advance(state, 0, null, null, FLOOR, false, false, DWELL);

        assertThat(state.pendingLane()).isEqualTo(SelfHealLane.SELF_HEAL_LIKELY);
        assertThat(state.pendingCycles()).isEqualTo(DWELL - 1);

        // ...and the very next complete cycle therefore commits, exactly as if the blind one
        // had never happened.
        state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, true, DWELL);
        assertThat(state.displayedLane()).isEqualTo(SelfHealLane.SELF_HEAL_LIKELY);
    }

    @Test
    void aRevertingCandidateMidDwellResetsTheCounter() {
        DwellState state = DwellState.initial();
        state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, true, DWELL);
        state = DwellStateMachine.advance(state, 10, 0.722, 1.0, FLOOR, false, true, DWELL);
        assertThat(state.pendingCycles()).isEqualTo(2);

        // n drops back below the floor — the candidate reverts to the currently displayed lane.
        state = DwellStateMachine.advance(state, 3, null, null, FLOOR, false, true, DWELL);

        assertThat(state.pendingLane()).isNull();
        assertThat(state.pendingCycles()).isZero();
    }

    @Test
    void aDisplayedLikelyLaneHoldsThroughItsOwnExitBandRatherThanTheStricterEnterThreshold() {
        // LB 0.65 fails the 0.70 ENTER threshold but is still above the 0.60 EXIT — must hold.
        DwellState displayedLikely = new DwellState(SelfHealLane.SELF_HEAL_LIKELY, null, 0);

        DwellState next = DwellStateMachine.advance(displayedLikely, 11, 0.65, 0.95, FLOOR, false, true, DWELL);

        assertThat(next.displayedLane()).isEqualTo(SelfHealLane.SELF_HEAL_LIKELY);
        assertThat(next.pendingLane()).isNull();
    }

    @Test
    void aDisplayedLikelyLaneLeavesOnceItsExitBandIsActuallyCrossed() {
        DwellState displayedLikely = new DwellState(SelfHealLane.SELF_HEAL_LIKELY, null, 0);
        DwellState state = displayedLikely;
        for (int i = 0; i < DWELL; i++) {
            // LB 0.552 (the baseline's documented "exits legitimately" boundary) — below 0.60.
            state = DwellStateMachine.advance(state, 12, 0.552, 0.85, FLOOR, false, true, DWELL);
        }

        assertThat(state.displayedLane()).isEqualTo(SelfHealLane.SELF_HEAL_MIXED);
    }

    @Test
    void aDisplayedUnlikelyLaneHoldsThroughItsOwnExitBand() {
        DwellState displayedUnlikely = new DwellState(SelfHealLane.SELF_HEAL_UNLIKELY, null, 0);

        DwellState next = DwellStateMachine.advance(displayedUnlikely, 11, 0.05, 0.35, FLOOR, false, true, DWELL);

        assertThat(next.displayedLane()).isEqualTo(SelfHealLane.SELF_HEAL_UNLIKELY);
    }

    @Test
    void midSpellARiskDecreasingCandidateIsSuppressedOutright() {
        // displayed UNLIKELY (risk 2), live spell open, candidate would be MIXED (risk 1) — a
        // risk-DECREASE — must be suppressed, never even starting a dwell.
        DwellState displayedUnlikely = new DwellState(SelfHealLane.SELF_HEAL_UNLIKELY, null, 0);

        DwellState next = DwellStateMachine.advance(displayedUnlikely, 12, 0.35, 0.55, FLOOR, true, true, DWELL);

        assertThat(next.displayedLane()).isEqualTo(SelfHealLane.SELF_HEAL_UNLIKELY);
        assertThat(next.pendingLane()).isNull();
        assertThat(next.pendingCycles()).isZero();
    }

    @Test
    void midSpellARiskIncreasingCandidateIsNotSuppressed() {
        // displayed MIXED (risk 1), live spell open, candidate UNLIKELY (risk 2) — increasing,
        // must proceed through the normal dwell.
        DwellState displayedMixed = new DwellState(SelfHealLane.SELF_HEAL_MIXED, null, 0);

        DwellState next = DwellStateMachine.advance(displayedMixed, 12, 0.02, 0.28, FLOOR, true, true, DWELL);

        assertThat(next.pendingLane()).isEqualTo(SelfHealLane.SELF_HEAL_UNLIKELY);
        assertThat(next.pendingCycles()).isEqualTo(1);
    }

    @Test
    void midSpellTransitionsIntoInsufficientHistorySitOutsideTheRiskOrderAndAreNeverSuppressed() {
        // displayed LIKELY, live spell open, n drops below the floor — must still be allowed to
        // start moving toward INSUFFICIENT_HISTORY (an evidence state, not a risk claim).
        DwellState displayedLikely = new DwellState(SelfHealLane.SELF_HEAL_LIKELY, null, 0);

        DwellState next = DwellStateMachine.advance(displayedLikely, 3, null, null, FLOOR, true, true, DWELL);

        assertThat(next.pendingLane()).isEqualTo(SelfHealLane.INSUFFICIENT_HISTORY);
        assertThat(next.pendingCycles()).isEqualTo(1);
    }

    @Test
    void withoutALiveSpellARiskDecreasingCandidateProceedsNormally() {
        DwellState displayedUnlikely = new DwellState(SelfHealLane.SELF_HEAL_UNLIKELY, null, 0);

        DwellState next = DwellStateMachine.advance(displayedUnlikely, 12, 0.35, 0.55, FLOOR, false, true, DWELL);

        assertThat(next.pendingLane()).isEqualTo(SelfHealLane.SELF_HEAL_MIXED);
        assertThat(next.pendingCycles()).isEqualTo(1);
    }
}

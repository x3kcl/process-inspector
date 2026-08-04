package io.inspector.selfheal;

/**
 * The pure per-class computation result (RETRYING-RISK-LANE.md §3.2) — everything derivable
 * from completed spells alone, BEFORE the §4.2 server-side dwell/hysteresis machine decides
 * what actually gets DISPLAYED. {@code rawLane} is the enter-threshold classification (§4.1),
 * never served directly (see {@code DwellStateMachine}/{@code SelfHealStatsService}).
 */
public record RawSelfHealStats(
        int n,
        int healed,
        Double wilsonLow,
        Double wilsonHigh,
        Long ttsP50Seconds,
        Long ttsP90Seconds,
        int excludedSpells,
        boolean truncationTainted,
        SelfHealLane rawLane) {

    public static RawSelfHealStats insufficientHistory() {
        return new RawSelfHealStats(0, 0, null, null, null, null, 0, false, SelfHealLane.INSUFFICIENT_HISTORY);
    }
}

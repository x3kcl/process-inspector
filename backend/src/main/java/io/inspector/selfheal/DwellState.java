package io.inspector.selfheal;

/**
 * One class's server-side hysteresis/dwell state (RETRYING-RISK-LANE.md §4.2 rule 3) — the
 * machine that turns a freshly computed raw statistic into the DISPLAYED lane every consumer
 * reads. In-memory, single-instance BFF; re-arms conservatively (from whichever lane was last
 * served, at zero dwell progress) on restart — never persisted, per the design.
 */
public record DwellState(SelfHealLane displayedLane, SelfHealLane pendingLane, int pendingCycles) {

    public static DwellState initial() {
        return new DwellState(SelfHealLane.INSUFFICIENT_HISTORY, null, 0);
    }
}

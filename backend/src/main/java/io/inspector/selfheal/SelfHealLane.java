package io.inspector.selfheal;

/**
 * The RETRYING risk lane's four displayed states (RETRYING-RISK-LANE.md §4.1, #351).
 * {@code INSUFFICIENT_HISTORY} is the NORMAL case, not an edge case — measured 2026-08-04
 * (docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md): zero unconfounded completed spells exist
 * anywhere in the pilot ledger, so every class renders this lane for the foreseeable future.
 * It sits OUTSIDE the LIKELY&lt;MIXED&lt;UNLIKELY risk order (§4.2 rule 4) — it is an evidence
 * state, never a risk claim.
 */
public enum SelfHealLane {
    SELF_HEAL_LIKELY,
    SELF_HEAL_MIXED,
    SELF_HEAL_UNLIKELY,
    INSUFFICIENT_HISTORY
}

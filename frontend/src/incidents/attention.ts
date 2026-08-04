// Reconciliation of #352's client-side self-heal-risk sort with #353's server-side attention
// score (ALARM-COST-MODEL.md §3.1/§4.1/§11, issue #354). Rung-1 pure — no React, no IO — mirroring
// incidents/selfHeal.ts's own idiom.
//
// THE CONFLICT (read this before touching either comparator): #352 sorts each not-yet-resolved
// Incident Ledger section by `compareSelfHealRisk` (selfHeal.ts), a client-side ranking over the
// R2 `selfHeal.lane` badge. #353 later added a server-computed attention score
// `A(c) = F(c)·R(c)·M(c)·S(c)` (§4.1) to the SAME `IncidentSummary` rows — and `S(c)` is DERIVED
// FROM THE SAME `selfHeal.lane` (§11: "the join therefore maps lane → p_heal at the §4.1 band
// midpoints"). Sorting by `compareSelfHealRisk` on top of an already attention-ordered list would
// apply that one self-heal signal TWICE, and — worse — silently override the server's considered
// order with a client-only re-derivation. §3.1 is explicit that the whole point of this design is
// that ordering is server-computed and explainable: the server ordering must win.
//
// THE RECONCILIATION: when a row carries a server `attention` score, rank by that score alone
// (mirroring the backend's own tie-break, `AttentionOrdering.BY_ATTENTION` — score DESC, total
// DESC, signatureHash ASC — so the client never presents an order the server itself wouldn't
// choose). When `attention` is absent — the shipped, flag-off, EXPECTED case today (§7: the R1
// data-maturity gate is NOT MET; §11: `inspector.triage.attention-ordering` defaults false, so
// every response omits the block) — fall back to exactly #352's `compareSelfHealRisk`, unchanged.
// This is why the fallback path is the one every existing #352 test still exercises, and why it
// must stay the well-tested "normal" path rather than an edge case.
//
// WHERE this applies: per ALARM-COST-MODEL.md §11, the incident LIST keeps its server order
// (`lastSeen DESC`) — the score is never used to reorder the fetch itself. It orders WITHIN the
// client-derived sections (REGRESSED/OPEN/QUIET) exactly where #352's own risk-ranked view
// already lived, in incidents/sections.ts. RESOLVED/archived stay `byLastSeenDesc` (historical,
// no attention left to rank) — untouched by this module.
import type { AttentionScore, IncidentSummary } from '../api/model'
import { compareSelfHealRisk } from './selfHeal'

/** Score DESC → total DESC → signatureHash ASC — the SAME total order as the backend's
 *  `AttentionOrdering.BY_ATTENTION` (ALARM-COST-MODEL.md §11), so a client re-sort of an
 *  already-scored list is a no-op rather than a second, possibly-divergent ranking. */
function compareByAttentionScore(a: IncidentSummary, b: IncidentSummary): number {
  const scoreDiff = (b.attention?.score ?? 0) - (a.attention?.score ?? 0)
  if (scoreDiff !== 0) return scoreDiff
  const totalDiff = (b.lastTotal ?? 0) - (a.lastTotal ?? 0)
  if (totalDiff !== 0) return totalDiff
  return (a.signatureHash ?? '').localeCompare(b.signatureHash ?? '')
}

/**
 * The Incident Ledger section comparator (used by `sections.ts#bucketIncidents` in place of a
 * bare `compareSelfHealRisk`). Ordering only — never removes a row (R-BAU-01 never-hide, proven
 * by `sections.test.ts`'s reorder-survival assertion).
 *
 * A row is treated as "scored" only when BOTH sides of a comparison carry `attention` — the flag
 * is a single deployment-wide switch (§7/§11), so in practice every row has it or none do; a
 * comparison that finds only one side scored (a transient rollout edge, never expected in
 * steady state) still fails toward the well-tested self-heal fallback rather than mixing an
 * incomparable score against a rank.
 */
export function compareIncidentOrder(a: IncidentSummary, b: IncidentSummary): number {
  if (a.attention !== undefined && b.attention !== undefined) {
    return compareByAttentionScore(a, b)
  }
  return compareSelfHealRisk(a, b)
}

/** The server's one-sentence rationale (ALARM-COST-MODEL.md §4.3, built exactly per §11's
 *  `AttentionRationale`) — rendered VERBATIM in the tooltip. Never recomposed from `factors`
 *  client-side: the design's explicit rule is that the tooltip shows "real numbers, not vibes"
 *  the SERVER computed (§4.1). `undefined` when the score is absent (flag off, or scoring was
 *  skipped for this row) — callers must render nothing, never fabricate a sentence. */
export function attentionRationale(attention: AttentionScore | undefined): string | undefined {
  return attention?.rationale
}

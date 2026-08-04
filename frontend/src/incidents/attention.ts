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
// THE RECONCILIATION: when EVERY row in the list carries a server `attention` score, rank by that
// score alone (mirroring the backend's own tie-break, `AttentionOrdering.BY_ATTENTION` — score
// DESC, total DESC, signatureHash ASC — so the client never presents an order the server itself
// wouldn't choose). When any row lacks it — the shipped, flag-off, EXPECTED case today (§7: the R1
// data-maturity gate is NOT MET; §11: `inspector.triage.attention-ordering` defaults false, so
// every response omits the block) — fall back to exactly #352's `compareSelfHealRisk`, unchanged.
// This is why the fallback path is the one every existing #352 test still exercises, and why it
// must stay the well-tested "normal" path rather than an edge case. The choice is made ONCE PER
// LIST, never per pair — see `incidentOrderComparator` for why that distinction is the whole
// correctness argument (a per-pair rule is non-transitive, and V8 sorts it into garbage silently).
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
 * Picks the Incident Ledger's section comparator ONCE for a whole list, and that "once" is the
 * entire point (review fix — the previous version was a per-PAIR rule and was provably
 * non-transitive).
 *
 * The old `compareIncidentOrder(a, b)` chose the attention path only when BOTH sides carried
 * `attention`, and `compareSelfHealRisk` otherwise. That is a different order relation depending
 * on which pair you look at, which admits a strict cycle. The executed counterexample:
 *
 * ```
 * A = { attention: { score: 5 }, selfHeal: { lane: 'INSUFFICIENT_HISTORY' } }
 * B = {                          selfHeal: { lane: 'SELF_HEAL_MIXED' } }        // no attention
 * C = { attention: { score: 1 }, selfHeal: { lane: 'SELF_HEAL_UNLIKELY' } }
 * cmp(A, C) < 0   (score 5 beats score 1)
 * cmp(C, B) < 0   (UNLIKELY outranks MIXED)
 * cmp(B, A) < 0   (MIXED outranks INSUFFICIENT_HISTORY)   ⇒  A < C < B < A
 * ```
 *
 * V8 does not throw on a broken comparator the way Java's TimSort does — it silently returns
 * *some* permutation, and those same three rows produced FOUR different orderings depending on
 * input order. That is a garbage sort in the REGRESSED / OPEN / QUIET sections, not a
 * theoretical wart.
 *
 * It is also REACHABLE in production, which the old doc comment denied ("a transient rollout
 * edge, never expected in steady state"): `AttentionScoreService.forClass` catches
 * `RuntimeException` PER CLASS and returns `null`, and `IncidentSummary` is `@JsonInclude
 * (NON_NULL)` — so one poisoned row, or a 5-minute model-cache TTL expiring mid-page, omits the
 * block for SOME rows and a mixed array arrives client-side with the flag fully on.
 *
 * The fix makes the rule a property of the ARRAY: every row scored ⇒ rank by score (a total
 * order on `score → lastTotal → signatureHash`); otherwise the whole list falls back to
 * `compareSelfHealRisk` (a total order on `riskRank → lastTotal → lastSeen`). Both are
 * lexicographic over a fixed key tuple, hence transitive, hence safe to hand to `sort`. Mixing
 * them within one sort is what was never sound.
 *
 * Ordering only — never removes a row (R-BAU-01 never-hide, proven by `sections.test.ts`'s
 * reorder-survival assertion).
 */
export function incidentOrderComparator(
  rows: readonly IncidentSummary[],
): (a: IncidentSummary, b: IncidentSummary) => number {
  return rows.every((row) => row.attention !== undefined)
    ? compareByAttentionScore
    : compareSelfHealRisk
}

/** The server's one-sentence rationale (ALARM-COST-MODEL.md §4.3, built exactly per §11's
 *  `AttentionRationale`) — rendered VERBATIM in the tooltip. Never recomposed from `factors`
 *  client-side: the design's explicit rule is that the tooltip shows "real numbers, not vibes"
 *  the SERVER computed (§4.1). `undefined` when the score is absent (flag off, or scoring was
 *  skipped for this row) — callers must render nothing, never fabricate a sentence. */
export function attentionRationale(attention: AttentionScore | undefined): string | undefined {
  return attention?.rationale
}

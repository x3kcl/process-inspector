// Reconciliation logic between #352's client self-heal-risk sort and #353's server attention
// score (ALARM-COST-MODEL.md §3.1/§11, issue #354). See attention.ts's doc comment for the full
// reasoning — this file proves both paths named there: attention-present ordering wins outright,
// and attention-absent degrades to EXACTLY #352's original `compareSelfHealRisk` behavior (the
// shipped, flag-off, expected-today case).
import { describe, expect, it } from 'vitest'
import type { AttentionScore, IncidentSummary } from '../api/model'
import { attentionRationale, incidentOrderComparator } from './attention'
import { compareSelfHealRisk } from './selfHeal'

/** Every ordering of `rows` — the only way to catch a comparator whose result depends on the
 *  order it happens to be handed its inputs (V8 never throws on a broken one, unlike Java). */
function permutations<T>(rows: T[]): T[][] {
  if (rows.length <= 1) return [rows]
  return rows.flatMap((row, index) =>
    permutations([...rows.slice(0, index), ...rows.slice(index + 1)]).map((rest) => [row, ...rest]),
  )
}

/** What `sections.ts` does: resolve the comparator ONCE from the list, then sort with it. */
function sortAsSectionsDo(rows: IncidentSummary[]): IncidentSummary[] {
  return [...rows].sort(incidentOrderComparator(rows))
}

function incident(overrides: Partial<IncidentSummary> & { id: number }): IncidentSummary {
  return {
    signatureHash: `sig-${String(overrides.id)}`,
    lastSeen: '2026-07-18T00:00:00Z',
    currentGeneration: true,
    ...overrides,
  }
}

function attention(score: number, rationale = `score ${String(score)}`): AttentionScore {
  return { score, rationale }
}

describe('compareIncidentOrder — attention-present path', () => {
  it('ranks by server attention score DESC when every side carries one, ignoring self-heal lane', () => {
    // The lower-scored incident has the "more urgent" self-heal lane (UNLIKELY) — if the old
    // compareSelfHealRisk were still driving this, it would sort FIRST. The server attention
    // score already folds in self-heal (§11 lane→p_heal), so it must win outright instead.
    const highScoreButLikelyToSelfHeal = incident({
      id: 1,
      attention: attention(9.5),
      selfHeal: { lane: 'SELF_HEAL_LIKELY', n: 14, healed: 12, excludedSpells: 0 },
    })
    const lowScoreButUnlikelyToSelfHeal = incident({
      id: 2,
      attention: attention(1.2),
      selfHeal: { lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 },
    })
    const result = sortAsSectionsDo([lowScoreButUnlikelyToSelfHeal, highScoreButLikelyToSelfHeal])
    expect(result).toEqual([highScoreButLikelyToSelfHeal, lowScoreButUnlikelyToSelfHeal])
  })

  it('breaks a score tie by lastTotal DESC, mirroring the backend AttentionOrdering tie-break', () => {
    const smaller = incident({ id: 1, attention: attention(5), lastTotal: 8 })
    const bigger = incident({ id: 2, attention: attention(5), lastTotal: 21 })
    expect(sortAsSectionsDo([smaller, bigger])).toEqual([bigger, smaller])
  })

  it('breaks a score+total tie by signatureHash ASC, mirroring the backend tie-break exactly', () => {
    const b = incident({ id: 1, signatureHash: 'sig-b', attention: attention(5), lastTotal: 8 })
    const a = incident({ id: 2, signatureHash: 'sig-a', attention: attention(5), lastTotal: 8 })
    expect(sortAsSectionsDo([b, a])).toEqual([a, b])
  })

  it('treats a missing score as 0.0, never crashing or burying the card ahead of an unscored one', () => {
    const scored = incident({ id: 1, attention: { rationale: 'no numeric score' } })
    const unscored = incident({ id: 2, attention: attention(0.001) })
    expect(sortAsSectionsDo([scored, unscored])).toEqual([unscored, scored])
  })

  it('is transitive across every input permutation when every row is scored', () => {
    const rows = [
      incident({ id: 1, signatureHash: 'sig-a', attention: attention(5), lastTotal: 3 }),
      incident({ id: 2, signatureHash: 'sig-b', attention: attention(1), lastTotal: 90 }),
      incident({ id: 3, signatureHash: 'sig-c', attention: attention(5), lastTotal: 40 }),
    ]

    for (const permutation of permutations(rows)) {
      expect(sortAsSectionsDo(permutation).map((row) => row.signatureHash)).toEqual([
        'sig-c', // score 5, total 40
        'sig-a', // score 5, total 3
        'sig-b', // score 1
      ])
    }
  })
})

// The confirmed HIGH defect this module was rewritten for. `compareIncidentOrder(a, b)` used to
// pick its rule PER PAIR — attention path only when BOTH sides carried `attention`, self-heal rank
// otherwise — which is a different order relation on different pairs and therefore admits a strict
// cycle. These exact three rows produce cmp(A,C) < 0, cmp(C,B) < 0 and cmp(B,A) < 0, i.e.
// A < C < B < A; handed to `Array.prototype.sort` they yielded FOUR different orderings depending
// on input order, because V8 (unlike Java's TimSort) never throws on a broken comparator — it just
// returns some permutation. The old test only ever asserted a two-element PAIR, which is why CI
// stayed green over it.
//
// And it is REACHABLE with the flag fully on, contrary to the old "transient rollout edge" comment:
// `AttentionScoreService.forClass` catches RuntimeException PER CLASS and returns null, and
// `IncidentSummary` is @JsonInclude(NON_NULL) — so one poisoned row, or the 5-minute attention
// model cache expiring mid-page, is enough to serve a mixed array.
describe('compareIncidentOrder cycle (the review counterexample) — one stable order, every way in', () => {
  const a = incident({
    id: 1,
    signatureHash: 'sig-a',
    attention: attention(5),
    selfHeal: { lane: 'INSUFFICIENT_HISTORY', n: 2, healed: 0, excludedSpells: 0 },
  })
  const b = incident({
    id: 2,
    signatureHash: 'sig-b',
    // No `attention` at all — the poisoned/mid-TTL row.
    selfHeal: { lane: 'SELF_HEAL_MIXED', n: 11, healed: 6, excludedSpells: 0 },
  })
  const c = incident({
    id: 3,
    signatureHash: 'sig-c',
    attention: attention(1),
    selfHeal: { lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 },
  })

  it('sorts all 6 permutations of the cyclic triple into ONE ordering', () => {
    const orders = permutations([a, b, c]).map((permutation) =>
      sortAsSectionsDo(permutation).map((row) => row.signatureHash),
    )

    // A mixed array degrades WHOLESALE to compareSelfHealRisk — a total order on
    // riskRank → lastTotal → lastSeen — so the answer is UNLIKELY, MIXED, INSUFFICIENT_HISTORY.
    for (const order of orders) {
      expect(order).toEqual(['sig-c', 'sig-b', 'sig-a'])
    }
    expect(new Set(orders.map((order) => order.join(','))).size).toBe(1)
  })

  it('degrades the whole mixed list to compareSelfHealRisk rather than mixing two rules', () => {
    const input = [a, b, c]
    expect(sortAsSectionsDo(input)).toEqual([...input].sort(compareSelfHealRisk))
  })
})

describe('compareIncidentOrder — attention-absent path (the shipped, flag-off, expected-today case)', () => {
  it('degrades to EXACTLY compareSelfHealRisk when neither side carries an attention score', () => {
    const likely = incident({
      id: 1,
      selfHeal: { lane: 'SELF_HEAL_LIKELY', n: 14, healed: 12, excludedSpells: 0 },
    })
    const unlikely = incident({
      id: 2,
      selfHeal: { lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 },
    })
    const insufficient = incident({
      id: 3,
      selfHeal: { lane: 'INSUFFICIENT_HISTORY', n: 2, healed: 0, excludedSpells: 0 },
    })
    const input = [likely, insufficient, unlikely]
    expect(sortAsSectionsDo(input)).toEqual([...input].sort(compareSelfHealRisk))
    expect(sortAsSectionsDo(input)).toEqual([unlikely, insufficient, likely])
  })

  it('falls back to compareSelfHealRisk for the WHOLE list when even one row lacks a score', () => {
    const scored = incident({ id: 1, attention: attention(9), selfHeal: undefined })
    const unscored = incident({
      id: 2,
      selfHeal: { lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 },
    })
    const input = [scored, unscored]

    expect(incidentOrderComparator(input)).toBe(compareSelfHealRisk)
    expect(sortAsSectionsDo(input)).toEqual([...input].sort(compareSelfHealRisk))
  })
})

describe('attentionRationale', () => {
  it('renders the server rationale verbatim', () => {
    expect(attentionRationale(attention(4.2, '21 failing · last seen 2 min ago'))).toBe(
      '21 failing · last seen 2 min ago',
    )
  })

  it('is undefined when attention is absent — never fabricates a rationale', () => {
    expect(attentionRationale(undefined)).toBeUndefined()
  })

  it('is undefined when attention is present but carries no rationale (score-only, unusual but tolerated)', () => {
    expect(attentionRationale({ score: 4.2 })).toBeUndefined()
  })
})

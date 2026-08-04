// Reconciliation logic between #352's client self-heal-risk sort and #353's server attention
// score (ALARM-COST-MODEL.md §3.1/§11, issue #354). See attention.ts's doc comment for the full
// reasoning — this file proves both paths named there: attention-present ordering wins outright,
// and attention-absent degrades to EXACTLY #352's original `compareSelfHealRisk` behavior (the
// shipped, flag-off, expected-today case).
import { describe, expect, it } from 'vitest'
import type { AttentionScore, IncidentSummary } from '../api/model'
import { attentionRationale, compareIncidentOrder } from './attention'
import { compareSelfHealRisk } from './selfHeal'

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
    const result = [lowScoreButUnlikelyToSelfHeal, highScoreButLikelyToSelfHeal].sort(
      compareIncidentOrder,
    )
    expect(result).toEqual([highScoreButLikelyToSelfHeal, lowScoreButUnlikelyToSelfHeal])
  })

  it('breaks a score tie by lastTotal DESC, mirroring the backend AttentionOrdering tie-break', () => {
    const smaller = incident({ id: 1, attention: attention(5), lastTotal: 8 })
    const bigger = incident({ id: 2, attention: attention(5), lastTotal: 21 })
    expect([smaller, bigger].sort(compareIncidentOrder)).toEqual([bigger, smaller])
  })

  it('breaks a score+total tie by signatureHash ASC, mirroring the backend tie-break exactly', () => {
    const b = incident({ id: 1, signatureHash: 'sig-b', attention: attention(5), lastTotal: 8 })
    const a = incident({ id: 2, signatureHash: 'sig-a', attention: attention(5), lastTotal: 8 })
    expect([b, a].sort(compareIncidentOrder)).toEqual([a, b])
  })

  it('treats a missing score as 0.0, never crashing or burying the card ahead of an unscored one', () => {
    const scored = incident({ id: 1, attention: { rationale: 'no numeric score' } })
    const unscored = incident({ id: 2, attention: attention(0.001) })
    expect([scored, unscored].sort(compareIncidentOrder)).toEqual([unscored, scored])
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
    expect([...input].sort(compareIncidentOrder)).toEqual([...input].sort(compareSelfHealRisk))
    expect([...input].sort(compareIncidentOrder)).toEqual([unlikely, insufficient, likely])
  })

  it('falls back to compareSelfHealRisk when only ONE side carries a score (never mixes an incomparable score against a rank)', () => {
    const scored = incident({ id: 1, attention: attention(9), selfHeal: undefined })
    const unscored = incident({
      id: 2,
      selfHeal: { lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 },
    })
    // Same result as calling compareSelfHealRisk directly on this pair.
    expect(compareIncidentOrder(scored, unscored)).toBe(compareSelfHealRisk(scored, unscored))
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

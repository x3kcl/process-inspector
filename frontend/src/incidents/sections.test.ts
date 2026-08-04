import { describe, expect, it } from 'vitest'
import type { IncidentSummary } from '../api/model'
import { bucketIncidents } from './sections'

function incident(overrides: Partial<IncidentSummary> & { id: number }): IncidentSummary {
  return {
    signatureHash: `sig-${String(overrides.id)}`,
    lastSeen: '2026-07-18T00:00:00Z',
    currentGeneration: true,
    ...overrides,
  }
}

describe('bucketIncidents', () => {
  it('sorts current-generation incidents into REGRESSED / OPEN / QUIET / RESOLVED', () => {
    const regressed = incident({ id: 1, state: 'REGRESSED' })
    const open = incident({ id: 2, state: 'OPEN', quiet: false })
    const quiet = incident({ id: 3, state: 'OPEN', quiet: true })
    const resolved = incident({ id: 4, state: 'RESOLVED' })

    const result = bucketIncidents([regressed, open, quiet, resolved])
    expect(result.regressed).toEqual([regressed])
    expect(result.open).toEqual([open])
    expect(result.quiet).toEqual([quiet])
    expect(result.resolved).toEqual([resolved])
    expect(result.archived).toEqual([])
  })

  it('sends any non-current-generation incident to archived, regardless of state', () => {
    const archivedRegressed = incident({ id: 1, state: 'REGRESSED', currentGeneration: false })
    const archivedResolved = incident({ id: 2, state: 'RESOLVED', currentGeneration: false })

    const result = bucketIncidents([archivedRegressed, archivedResolved])
    expect(result.archived).toEqual(
      [archivedRegressed, archivedResolved].sort((a, b) =>
        (b.lastSeen ?? '').localeCompare(a.lastSeen ?? ''),
      ),
    )
    expect(result.regressed).toEqual([])
    expect(result.resolved).toEqual([])
  })

  it('defaults a missing currentGeneration to current (visible), never archived', () => {
    const noFlag = incident({ id: 1, state: 'OPEN' })
    delete noFlag.currentGeneration
    expect(bucketIncidents([noFlag]).open).toEqual([noFlag])
    expect(bucketIncidents([noFlag]).archived).toEqual([])
  })

  it('defaults an unrecognized or missing state to OPEN, never dropping the row', () => {
    const noState = incident({ id: 1 })
    delete noState.state
    const weirdState = incident({ id: 2, state: 'SOMETHING_NEW' })
    const result = bucketIncidents([noState, weirdState])
    expect(result.open).toContainEqual(noState)
    expect(result.open).toContainEqual(weirdState)
  })

  it('orders each not-yet-resolved bucket by lastSeen descending when self-heal risk ties', () => {
    const older = incident({ id: 1, state: 'OPEN', lastSeen: '2026-07-01T00:00:00Z' })
    const newer = incident({ id: 2, state: 'OPEN', lastSeen: '2026-07-15T00:00:00Z' })
    expect(bucketIncidents([older, newer]).open).toEqual([newer, older])
  })

  it('risk-ranks the OPEN section by self-heal lane when no server attention score is present (RETRYING-RISK-LANE.md §10, issue #352 scope 2) — the shipped, flag-off, expected-today case (ALARM-COST-MODEL.md §7/§11, #354)', () => {
    const likely = incident({
      id: 1,
      state: 'OPEN',
      selfHeal: { lane: 'SELF_HEAL_LIKELY', n: 14, healed: 12, excludedSpells: 0 },
    })
    const unlikely = incident({
      id: 2,
      state: 'OPEN',
      selfHeal: { lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 },
    })
    const insufficient = incident({
      id: 3,
      state: 'OPEN',
      selfHeal: { lane: 'INSUFFICIENT_HISTORY', n: 2, healed: 0, excludedSpells: 0 },
    })

    const result = bucketIncidents([likely, insufficient, unlikely])
    expect(result.open).toEqual([unlikely, insufficient, likely])
    // Ordering only — every incident is still present, just reordered (R-BAU-01 never-hide).
    expect(result.open).toHaveLength(3)
  })

  // #354 reconciliation (ALARM-COST-MODEL.md §3.1/§11): #353 later attached a SERVER attention
  // score to these same rows, and it already folds in self-heal (§11 lane→p_heal). Once present,
  // it must supersede the self-heal-risk comparator above outright — never stack on top of it.
  it('supersedes the self-heal-lane order with the server attention score once present, ignoring lane (#354 reconciliation)', () => {
    // Same three incidents/lanes as the self-heal-only test above, but now every row carries a
    // server attention score that ranks the OPPOSITE way the lane alone would — proving the
    // server order wins outright rather than being blended with or overridden by the client lane
    // ranking.
    const likelyButHighestScore = incident({
      id: 1,
      state: 'OPEN',
      selfHeal: { lane: 'SELF_HEAL_LIKELY', n: 14, healed: 12, excludedSpells: 0 },
      attention: { score: 9.1, rationale: '21 failing · proven self-healer, ranked first anyway' },
    })
    const unlikelyButLowestScore = incident({
      id: 2,
      state: 'OPEN',
      selfHeal: { lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 },
      attention: { score: 0.4, rationale: '8 failing · rarely self-heals, ranked last anyway' },
    })
    const insufficientMidScore = incident({
      id: 3,
      state: 'OPEN',
      selfHeal: { lane: 'INSUFFICIENT_HISTORY', n: 2, healed: 0, excludedSpells: 0 },
      attention: { score: 3.0, rationale: '5 failing · no self-heal history' },
    })

    const result = bucketIncidents([
      unlikelyButLowestScore,
      insufficientMidScore,
      likelyButHighestScore,
    ])
    // Attention score DESC — the exact inverse of what compareSelfHealRisk alone would produce.
    expect(result.open).toEqual([
      likelyButHighestScore,
      insufficientMidScore,
      unlikelyButLowestScore,
    ])
    expect(result.open).toHaveLength(3)
  })

  it('never hides a card across any bucket, regardless of which incidents carry an attention score (R-BAU-01)', () => {
    const scored = incident({
      id: 1,
      state: 'REGRESSED',
      attention: { score: 5, rationale: 'scored' },
    })
    const unscored = incident({ id: 2, state: 'OPEN' })
    const quiet = incident({ id: 3, state: 'OPEN', quiet: true })
    const resolved = incident({ id: 4, state: 'RESOLVED' })
    const archived = incident({ id: 5, state: 'REGRESSED', currentGeneration: false })
    const input = [scored, unscored, quiet, resolved, archived]

    const result = bucketIncidents(input)
    const survivors = [
      ...result.regressed,
      ...result.open,
      ...result.quiet,
      ...result.resolved,
      ...result.archived,
    ]
    expect(survivors).toHaveLength(input.length)
    expect(new Set(survivors.map((i) => i.id))).toEqual(new Set(input.map((i) => i.id)))
  })
})

import { describe, expect, it } from 'vitest'
import type { IncidentSummary, SelfHealStats } from '../api/model'
import {
  compareSelfHealRisk,
  selfHealBadgeContent,
  selfHealCaveat,
  selfHealScopeNote,
} from './selfHeal'

function stats(overrides: Partial<SelfHealStats>): SelfHealStats {
  return { lane: 'INSUFFICIENT_HISTORY', n: 0, healed: 0, excludedSpells: 0, ...overrides }
}

describe('selfHealBadgeContent — exact copy per RETRYING-RISK-LANE.md §4.1', () => {
  it('is undefined when the selfHeal block is absent — no fabricated state', () => {
    expect(selfHealBadgeContent(undefined)).toBeUndefined()
  })

  it('is undefined for a lane literal this build does not recognize (fail toward rendering nothing)', () => {
    expect(selfHealBadgeContent(stats({ lane: 'SOMETHING_NEW' }))).toBeUndefined()
  })

  it('INSUFFICIENT_HISTORY — the expected, common-for-a-long-time default state', () => {
    const content = selfHealBadgeContent(stats({ lane: 'INSUFFICIENT_HISTORY', n: 3 }))
    expect(content?.lane).toBe('INSUFFICIENT_HISTORY')
    expect(content?.text).toBe('no reliable self-heal history yet (3 of 10 spells observed)')
  })

  it('INSUFFICIENT_HISTORY caps the progress display at the floor (dwell can hold n past 10, §4.2 rule 3)', () => {
    const content = selfHealBadgeContent(stats({ lane: 'INSUFFICIENT_HISTORY', n: 12 }))
    expect(content?.text).toBe('no reliable self-heal history yet (10 of 10 spells observed)')
  })

  it('SELF_HEAL_LIKELY includes the typical-duration clause when ttsP90Seconds is present', () => {
    const content = selfHealBadgeContent(
      stats({ lane: 'SELF_HEAL_LIKELY', n: 14, healed: 12, ttsP90Seconds: 480 }),
    )
    expect(content?.text).toBe('usually self-heals (12/14, typically ≤ 8 min)')
  })

  it('SELF_HEAL_LIKELY rounds the typical duration UP so the "≤" bound never understates', () => {
    // 481s = 8.02 min — must read "≤ 9 min", not "≤ 8 min" (floor/round-nearest would understate).
    const content = selfHealBadgeContent(
      stats({ lane: 'SELF_HEAL_LIKELY', n: 14, healed: 12, ttsP90Seconds: 481 }),
    )
    expect(content?.text).toBe('usually self-heals (12/14, typically ≤ 9 min)')
  })

  it('SELF_HEAL_LIKELY omits the typical-duration clause when ttsP90Seconds is absent (defensive — DTO cannot express it below floor/zero-healed)', () => {
    const content = selfHealBadgeContent(stats({ lane: 'SELF_HEAL_LIKELY', n: 14, healed: 12 }))
    expect(content?.text).toBe('usually self-heals (12/14)')
  })

  it('SELF_HEAL_MIXED', () => {
    const content = selfHealBadgeContent(stats({ lane: 'SELF_HEAL_MIXED', n: 11, healed: 6 }))
    expect(content?.text).toBe('mixed self-heal record (6/11)')
  })

  it('SELF_HEAL_UNLIKELY', () => {
    const content = selfHealBadgeContent(stats({ lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1 }))
    expect(content?.text).toBe('rarely self-heals (1/12) — treat like FAILED')
  })
})

describe('selfHealCaveat — honesty rails (§5 "Truncation propagates")', () => {
  it('is undefined when nothing was excluded', () => {
    expect(selfHealCaveat(stats({ excludedSpells: 0, truncationTainted: false }))).toBeUndefined()
  })

  it('names the truncated-scan caveat when truncationTainted is true, singular phrasing at 1', () => {
    expect(selfHealCaveat(stats({ excludedSpells: 1, truncationTainted: true }))).toBe(
      '1 spell is unmeasurable: truncated scan',
    )
  })

  it('names the truncated-scan caveat, plural phrasing above 1 (design example wording)', () => {
    expect(selfHealCaveat(stats({ excludedSpells: 2, truncationTainted: true }))).toBe(
      '2 spells are unmeasurable: truncated scan',
    )
  })

  it('names a generic exclusion caveat when spells were excluded but none were truncated', () => {
    expect(selfHealCaveat(stats({ excludedSpells: 2, truncationTainted: false }))).toBe(
      '2 spells excluded from this statistic (operator-confounded, a sampling gap, an unreachable engine, ' +
        'or already in progress when the window opened — not counted toward the rate)',
    )
  })
})

describe('selfHealScopeNote — R-SAFE-17 fleet-wide-statistic caveat', () => {
  it('is undefined for an unscoped (full-visibility) caller', () => {
    expect(selfHealScopeNote(false)).toBeUndefined()
    expect(selfHealScopeNote(undefined)).toBeUndefined()
  })

  it('names the fleet-wide caveat for a scope-restricted caller', () => {
    expect(selfHealScopeNote(true)).toBe(
      'fleet-wide statistic — may include engines outside your scope',
    )
  })
})

describe('compareSelfHealRisk — attention-first ordering, ordering ONLY (never hides)', () => {
  function incident(overrides: Partial<IncidentSummary> & { id: number }): IncidentSummary {
    return {
      signatureHash: `sig-${String(overrides.id)}`,
      lastSeen: '2026-07-18T00:00:00Z',
      ...overrides,
    }
  }

  it('orders SELF_HEAL_UNLIKELY -> MIXED -> INSUFFICIENT_HISTORY -> LIKELY, per RETRYING-RISK-LANE.md §10', () => {
    const likely = incident({ id: 1, selfHeal: stats({ lane: 'SELF_HEAL_LIKELY' }) })
    const insufficient = incident({ id: 2, selfHeal: stats({ lane: 'INSUFFICIENT_HISTORY' }) })
    const mixed = incident({ id: 3, selfHeal: stats({ lane: 'SELF_HEAL_MIXED' }) })
    const unlikely = incident({ id: 4, selfHeal: stats({ lane: 'SELF_HEAL_UNLIKELY' }) })

    const sorted = [likely, insufficient, mixed, unlikely].sort(compareSelfHealRisk)
    expect(sorted).toEqual([unlikely, mixed, insufficient, likely])
  })

  it('treats an absent selfHeal block the same as INSUFFICIENT_HISTORY — never sorted as reassuring', () => {
    const likely = incident({ id: 1, selfHeal: stats({ lane: 'SELF_HEAL_LIKELY' }) })
    const noBlock = incident({ id: 2 })
    const unlikely = incident({ id: 3, selfHeal: stats({ lane: 'SELF_HEAL_UNLIKELY' }) })

    const sorted = [likely, noBlock, unlikely].sort(compareSelfHealRisk)
    expect(sorted).toEqual([unlikely, noBlock, likely])
  })

  it('breaks ties within the same lane by live total descending ("ties by live total", §10)', () => {
    const small = incident({ id: 1, selfHeal: stats({ lane: 'SELF_HEAL_UNLIKELY' }), lastTotal: 3 })
    const big = incident({ id: 2, selfHeal: stats({ lane: 'SELF_HEAL_UNLIKELY' }), lastTotal: 40 })

    expect([small, big].sort(compareSelfHealRisk)).toEqual([big, small])
  })

  it('breaks remaining ties by lastSeen descending — the common case, since INSUFFICIENT_HISTORY is everywhere', () => {
    const older = incident({ id: 1, lastSeen: '2026-07-01T00:00:00Z' })
    const newer = incident({ id: 2, lastSeen: '2026-07-15T00:00:00Z' })

    expect([older, newer].sort(compareSelfHealRisk)).toEqual([newer, older])
  })
})

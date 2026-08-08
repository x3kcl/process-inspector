// @vitest-environment jsdom
// The self-heal lane badge (RETRYING-RISK-LANE.md §4.1, issue #352). Covers every lane state
// INCLUDING "insufficient history" — per the R2 baseline measurement
// (reviews/R2-SELFHEAL-BASELINE-2026-08.md) that is the expected, common-for-a-long-time state,
// not an edge case, so it gets first billing here rather than an afterthought. LIKELY/MIXED are
// not reachable end-to-end on this deployment (the transiently-failing harness seed #351
// deferred) — these are component-level tests over synthetic props, not an end-to-end proof.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { SelfHealStats } from '../api/model'
import { SelfHealBadge } from './SelfHealBadge'

afterEach(cleanup)

describe('SelfHealBadge', () => {
  it('renders nothing when the selfHeal block is absent (feature computation unavailable)', () => {
    const { container } = render(<SelfHealBadge selfHeal={undefined} />)
    expect(container.firstChild).toBeNull()
  })

  it('INSUFFICIENT_HISTORY — the default, expected-for-a-long-time state', () => {
    render(
      <SelfHealBadge
        selfHeal={{ lane: 'INSUFFICIENT_HISTORY', n: 3, healed: 0, excludedSpells: 0 }}
      />,
    )
    const badge = screen.getByText('no reliable self-heal history yet (3 of 10 spells observed)')
    expect(badge.className).toContain('lane-insufficient')
    expect(badge.getAttribute('title')).toMatch(/not enough completed/i)
  })

  it('SELF_HEAL_LIKELY — exact copy incl. typical-duration clause', () => {
    render(
      <SelfHealBadge
        selfHeal={{
          lane: 'SELF_HEAL_LIKELY',
          n: 14,
          healed: 12,
          ttsP90Seconds: 480,
          excludedSpells: 0,
        }}
      />,
    )
    const badge = screen.getByText('usually self-heals (12/14 past spells, typically ≤ 8 min)')
    expect(badge.className).toContain('lane-likely')
  })

  it('SELF_HEAL_MIXED — exact copy', () => {
    render(
      <SelfHealBadge selfHeal={{ lane: 'SELF_HEAL_MIXED', n: 11, healed: 6, excludedSpells: 0 }} />,
    )
    const badge = screen.getByText('mixed self-heal record (6/11)')
    expect(badge.className).toContain('lane-mixed')
  })

  it('SELF_HEAL_UNLIKELY — exact copy, "treat like FAILED"', () => {
    render(
      <SelfHealBadge
        selfHeal={{ lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 }}
      />,
    )
    const badge = screen.getByText('rarely self-heals (1/12) — treat like FAILED')
    expect(badge.className).toContain('lane-unlikely')
  })

  it('carries the truncation marker + tooltip when truncationTainted is true (§5 honesty rails)', () => {
    render(
      <SelfHealBadge
        selfHeal={{
          lane: 'INSUFFICIENT_HISTORY',
          n: 3,
          healed: 0,
          excludedSpells: 2,
          truncationTainted: true,
        }}
      />,
    )
    const marker = screen.getByText('≥ scan')
    expect(marker.getAttribute('title')).toBe('2 spells are unmeasurable: truncated scan')
    const badge = screen.getByText('no reliable self-heal history yet (3 of 10 spells observed)')
    expect(badge.getAttribute('title')).toContain('2 spells are unmeasurable: truncated scan')
  })

  it('does NOT render the truncation marker when nothing was truncated, even with exclusions', () => {
    render(
      <SelfHealBadge
        selfHeal={{
          lane: 'SELF_HEAL_MIXED',
          n: 11,
          healed: 6,
          excludedSpells: 1,
          truncationTainted: false,
        }}
      />,
    )
    expect(screen.queryByText('≥ scan')).toBeNull()
    const badge = screen.getByText('mixed self-heal record (6/11)')
    expect(badge.getAttribute('title')).toContain('1 spell excluded from this statistic')
  })

  it('appends the fleet-wide scope caveat for a scope-restricted caller (R-SAFE-17)', () => {
    render(
      <SelfHealBadge
        selfHeal={{ lane: 'SELF_HEAL_UNLIKELY', n: 12, healed: 1, excludedSpells: 0 }}
        partial
      />,
    )
    const badge = screen.getByText('rarely self-heals (1/12) — treat like FAILED')
    expect(badge.getAttribute('title')).toContain('fleet-wide statistic')
  })

  it('does not misreport an unrecognized lane literal as a known state (fails toward rendering nothing)', () => {
    const unknown = {
      lane: 'SOMETHING_NEW',
      n: 1,
      healed: 0,
      excludedSpells: 0,
    } as unknown as SelfHealStats
    const { container } = render(<SelfHealBadge selfHeal={unknown} />)
    expect(container.firstChild).toBeNull()
  })
})

import { describe, expect, it } from 'vitest'
import {
  enginePolicyTokens,
  isInactiveLifecycle,
  isReadOnlyMode,
  lifecycleGloss,
} from './enginePolicy'

// Usability W1#4, theme T6: engine policy/lifecycle must be visible at the point of
// action — a second NON-COLOR token next to the environment badge, plus a plain-language
// gloss on the dashboard card (greyed-with-reason, never silently omitted — R-SEM-17).

describe('isReadOnlyMode — accepts the WIRE form, not just the enum name', () => {
  it('matches the registry wire value "read-only" (the form the BFF actually sends)', () => {
    // RED on HEAD: the gates compared toUpperCase() against "READ_ONLY", which the
    // hyphenated wire value can never equal — read-only was invisible at point of action.
    expect(isReadOnlyMode('read-only')).toBe(true)
  })
  it('still matches the enum-style spelling', () => {
    expect(isReadOnlyMode('READ_ONLY')).toBe(true)
  })
  it('is false for read-write and unknown', () => {
    expect(isReadOnlyMode('read-write')).toBe(false)
    expect(isReadOnlyMode(undefined)).toBe(false)
  })
})

describe('isInactiveLifecycle', () => {
  it('active (or unknown, e.g. an old BFF) is not inactive', () => {
    expect(isInactiveLifecycle('active')).toBe(false)
    expect(isInactiveLifecycle(undefined)).toBe(false)
  })
  it('every non-active lifecycle is inactive', () => {
    for (const state of ['disabled', 'draft', 'probed', 'probe_failed', 'removed']) {
      expect(isInactiveLifecycle(state)).toBe(true)
    }
  })
})

describe('enginePolicyTokens — the second badge token (SPEC §10a: never color alone)', () => {
  it('renders READ-ONLY as its own literal token with an owner-policy title', () => {
    const tokens = enginePolicyTokens('read-only', 'active')
    expect(tokens).toHaveLength(1)
    expect(tokens[0].token).toBe('READ-ONLY')
    expect(tokens[0].title).toContain('engine owner')
    expect(tokens[0].title).toContain('not your role')
  })
  it('renders a non-active lifecycle as a literal token', () => {
    const tokens = enginePolicyTokens('read-write', 'disabled')
    expect(tokens).toHaveLength(1)
    expect(tokens[0].token).toBe('DISABLED')
  })
  it('stacks both tokens when a read-only engine is also not active', () => {
    expect(enginePolicyTokens('read-only', 'probe_failed').map((t) => t.token)).toEqual([
      'READ-ONLY',
      'PROBE FAILED',
    ])
  })
  it('renders nothing for a plain active read-write engine', () => {
    expect(enginePolicyTokens('read-write', 'active')).toEqual([])
    expect(enginePolicyTokens(undefined, undefined)).toEqual([])
  })
})

describe('lifecycleGloss — the dashboard greyed-with-REASON copy', () => {
  it('says who disabled a disabled engine', () => {
    expect(lifecycleGloss('disabled')).toContain('engine owner')
  })
  it('distinguishes onboarding states from disabled (never impersonates)', () => {
    expect(lifecycleGloss('draft')).toContain('onboard')
    expect(lifecycleGloss('probe_failed')).toContain('probe')
    expect(lifecycleGloss('draft')).not.toContain('engine owner')
  })
  it('an unknown lifecycle still yields honest copy naming the raw state', () => {
    expect(lifecycleGloss('hibernating')).toContain('hibernating')
  })
})

// The pure half of the dangerous-set re-auth protocol (IDP-SECURITY.md §5): the staleness
// decision the modals pre-empt with, and the checkpoint codec that survives the full-page
// OIDC round-trip. The DOM side (sessionStorage + navigation) is Playwright's territory.
import { describe, expect, it } from 'vitest'
import {
  decodeResume,
  GUILLOTINE_WARN_MS,
  sessionExpiryState,
  encodeResume,
  isReauthBody,
  reauthStale,
  reauthWindowMinutes,
} from './reauth'

const NOW = Date.parse('2026-07-10T12:00:00Z')

describe('reauthStale', () => {
  it('is optimistic with no hint — the BFF gate answers', () => {
    expect(reauthStale(undefined, NOW)).toBe(false)
  })

  it('trusts the server verdict when required is already true', () => {
    expect(reauthStale({ required: true }, NOW)).toBe(true)
  })

  it('goes stale client-side once freshUntil passes (me is cached with staleTime Infinity)', () => {
    const past = new Date(NOW - 1_000).toISOString()
    const future = new Date(NOW + 60_000).toISOString()
    expect(reauthStale({ required: false, freshUntil: past }, NOW)).toBe(true)
    expect(reauthStale({ required: false, freshUntil: future }, NOW)).toBe(false)
  })

  it('stays fresh when the hint has no freshUntil (exempt dev/break-glass session)', () => {
    expect(reauthStale({ required: false }, NOW)).toBe(false)
  })

  it('ignores an unparseable freshUntil rather than wedging the modal', () => {
    expect(reauthStale({ required: false, freshUntil: 'not-a-date' }, NOW)).toBe(false)
  })
})

describe('reauthWindowMinutes', () => {
  it('rounds the window to whole minutes with a 15-min default', () => {
    expect(reauthWindowMinutes(undefined)).toBe(15)
    expect(reauthWindowMinutes({ windowSeconds: 600 })).toBe(10)
    expect(reauthWindowMinutes({ windowSeconds: 30 })).toBe(1) // floor: never "0 minutes"
  })
})

describe('resume checkpoint codec', () => {
  it('round-trips a same-origin route within the TTL', () => {
    const raw = encodeResume('/instances/engine-a/pi-1?tab=errors#job-7', NOW)
    expect(decodeResume(raw, NOW + 5 * 60_000)).toBe('/instances/engine-a/pi-1?tab=errors#job-7')
  })

  it('expires after the TTL — a wandered-off login lands on /', () => {
    const raw = encodeResume('/search', NOW)
    expect(decodeResume(raw, NOW + 11 * 60_000)).toBeNull()
  })

  it('rejects a future-stamped checkpoint (clock skew / tampering)', () => {
    const raw = encodeResume('/search', NOW + 60_000)
    expect(decodeResume(raw, NOW)).toBeNull()
  })

  it('never restores an absolute or protocol-relative URL (open-redirect hygiene)', () => {
    expect(decodeResume(encodeResume('https://evil.example/', NOW), NOW)).toBeNull()
    expect(decodeResume(encodeResume('//evil.example/', NOW), NOW)).toBeNull()
    // Browsers normalise '/\' to '//' — a backslash anywhere is rejected (Copilot review).
    expect(decodeResume(encodeResume('/\\evil.example/', NOW), NOW)).toBeNull()
    expect(decodeResume(encodeResume('/search\\..\\x', NOW), NOW)).toBeNull()
  })

  it('shrugs off garbage', () => {
    expect(decodeResume(null, NOW)).toBeNull()
    expect(decodeResume('not json', NOW)).toBeNull()
    expect(decodeResume('{"href":42,"ts":"x"}', NOW)).toBeNull()
    expect(decodeResume('[]', NOW)).toBeNull()
  })
})

describe('isReauthBody', () => {
  it('recognises the ProblemDetail challenge and nothing else', () => {
    expect(isReauthBody({ code: 'reauth-required' })).toBe(true)
    expect(isReauthBody({ code: 'rbac-denied' })).toBe(false)
    expect(isReauthBody(null)).toBe(false)
    expect(isReauthBody('reauth-required')).toBe(false)
  })
})

describe('sessionExpiryState — warn-before-guillotine (R-SAFE-07)', () => {
  it('is silent far from the cap and after it', () => {
    const inTwoHours = new Date(NOW + 2 * 3_600_000).toISOString()
    const past = new Date(NOW - 1_000).toISOString()
    expect(sessionExpiryState(inTwoHours, NOW).show).toBe(false)
    expect(sessionExpiryState(past, NOW).show).toBe(false)
    expect(sessionExpiryState(undefined, NOW).show).toBe(false)
    expect(sessionExpiryState('not-a-date', NOW).show).toBe(false)
  })

  it('warns inside the 30-min window with ceil minutes, never "0 minutes"', () => {
    const inTenMin = new Date(NOW + 10 * 60_000).toISOString()
    expect(sessionExpiryState(inTenMin, NOW)).toEqual({ show: true, minutesLeft: 10 })
    const inNinetySeconds = new Date(NOW + 90_000).toISOString()
    expect(sessionExpiryState(inNinetySeconds, NOW)).toEqual({ show: true, minutesLeft: 2 })
    const inTenSeconds = new Date(NOW + 10_000).toISOString()
    expect(sessionExpiryState(inTenSeconds, NOW)).toEqual({ show: true, minutesLeft: 1 })
  })

  it('the boundary: exactly 30 min out shows; a millisecond beyond stays silent', () => {
    expect(sessionExpiryState(new Date(NOW + GUILLOTINE_WARN_MS).toISOString(), NOW).show).toBe(
      true,
    )
    expect(
      sessionExpiryState(new Date(NOW + GUILLOTINE_WARN_MS + 1_000).toISOString(), NOW).show,
    ).toBe(false)
  })
})

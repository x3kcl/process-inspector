import { describe, expect, it } from 'vitest'
import {
  coveredEngineIds,
  deriveHonesty,
  groupCountsAreLowerBound,
  statusCountsAreLowerBound,
} from './honesty'

describe('deriveHonesty', () => {
  it('clean engines produce no lower bounds', () => {
    const honesty = deriveHonesty({ a: { ok: true }, b: { ok: true, dlqScan: 'complete' } })
    expect(honesty.failedEngines).toEqual([])
    expect(honesty.truncatedScans).toEqual([])
    expect(statusCountsAreLowerBound(honesty)).toBe(false)
    expect(groupCountsAreLowerBound(honesty)).toBe(false)
  })

  it('a failed engine makes ALL counts lower bounds', () => {
    const honesty = deriveHonesty({
      a: { ok: true },
      b: { ok: false, error: 'connect timeout' },
    })
    expect(honesty.failedEngines).toEqual([{ engineId: 'b', error: 'connect timeout' }])
    expect(statusCountsAreLowerBound(honesty)).toBe(true)
    expect(groupCountsAreLowerBound(honesty)).toBe(true)
  })

  it('a truncated DLQ scan bounds only the error-group counts', () => {
    const honesty = deriveHonesty({ a: { ok: true, dlqScan: 'truncated@5000' } })
    expect(honesty.truncatedScans).toEqual([{ engineId: 'a', marker: 'truncated@5000' }])
    expect(statusCountsAreLowerBound(honesty)).toBe(false)
    expect(groupCountsAreLowerBound(honesty)).toBe(true)
  })

  it('a failed engine without an error string gets a readable default, sorted output', () => {
    const honesty = deriveHonesty({ b: { ok: false }, a: { ok: false, error: 'x' } })
    expect(honesty.failedEngines.map((f) => f.engineId)).toEqual(['a', 'b'])
    expect(honesty.failedEngines[1]?.error).toBe('aggregation failed')
  })

  it('handles a missing perEngine map', () => {
    const honesty = deriveHonesty(undefined)
    expect(statusCountsAreLowerBound(honesty)).toBe(false)
  })

  it('collects out-of-scope dead-letters as a distinct, non-lower-bound note', () => {
    const honesty = deriveHonesty({
      b: { ok: true, dlqScan: 'complete', outOfScopeDeadletters: 3 },
      a: { ok: true, outOfScopeDeadletters: 1 },
    })
    // sorted by engineId, independent of the truncation/failed channels; a complete
    // DEADLETTER scan means the out-of-scope count is exact (floor: false).
    expect(honesty.outOfScope).toEqual([
      { engineId: 'a', count: 1, floor: false },
      { engineId: 'b', count: 3, floor: false },
    ])
    expect(statusCountsAreLowerBound(honesty)).toBe(false)
    expect(groupCountsAreLowerBound(honesty)).toBe(false)
  })

  it('floors the out-of-scope count when the DEADLETTER lane scan hit the cap', () => {
    const honesty = deriveHonesty({
      a: {
        ok: true,
        dlqScan: 'truncated@5000',
        outOfScopeDeadletters: 2,
        deadletterTruncated: true,
      },
    })
    // ≥2: the concrete number is a lower bound (rendered with a ≥ glyph upstream).
    expect(honesty.outOfScope).toEqual([{ engineId: 'a', count: 2, floor: true }])
  })

  it('does NOT floor out-of-scope when only a non-DEADLETTER lane truncated', () => {
    // dlqScan trips on any failure lane; deadletterTruncated is false, so the DEADLETTER
    // scan was complete and the out-of-scope count stays exact — the H1 distinction.
    const honesty = deriveHonesty({
      a: {
        ok: true,
        dlqScan: 'truncated@5000',
        outOfScopeDeadletters: 4,
        deadletterTruncated: false,
      },
    })
    expect(honesty.outOfScope).toEqual([{ engineId: 'a', count: 4, floor: false }])
  })

  it('omits zero, null (unknown/pre-6.8) and failed-engine out-of-scope counts', () => {
    const honesty = deriveHonesty({
      a: { ok: true, outOfScopeDeadletters: 0 },
      b: { ok: true, outOfScopeDeadletters: null as unknown as undefined },
      c: { ok: true },
      d: { ok: false, error: 'down', outOfScopeDeadletters: 9 },
    })
    expect(honesty.outOfScope).toEqual([])
  })

  it('reports out-of-scope even while the same engine group scan is truncated', () => {
    // A truncated GROUP scan (dlqScan) without the DEADLETTER-lane-specific flag leaves the
    // out-of-scope count exact — the two truncation channels are independent.
    const honesty = deriveHonesty({
      a: { ok: true, dlqScan: 'truncated@500', outOfScopeDeadletters: 2 },
    })
    expect(honesty.truncatedScans).toEqual([{ engineId: 'a', marker: 'truncated@500' }])
    expect(honesty.outOfScope).toEqual([{ engineId: 'a', count: 2, floor: false }])
  })
})

// #236: the failure-groups zero state claims "clean on every reachable engine" — the
// covered set is what that sentence may claim over; everything registered beyond it must
// be disclosed inline by the caller.
describe('coveredEngineIds (#236)', () => {
  it('returns only the engines that answered ok', () => {
    expect(
      coveredEngineIds({
        a: { ok: true },
        b: { ok: false, error: 'down' },
        c: { ok: true, dlqScan: 'truncated@500' },
      }),
    ).toEqual(['a', 'c'])
  })

  it('an absent envelope covers nothing', () => {
    expect(coveredEngineIds(undefined)).toEqual([])
  })
})

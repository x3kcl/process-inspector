import { describe, expect, it } from 'vitest'
import { deriveHonesty, groupCountsAreLowerBound, statusCountsAreLowerBound } from './honesty'

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
    // sorted by engineId, independent of the truncation/failed channels
    expect(honesty.outOfScope).toEqual([
      { engineId: 'a', count: 1 },
      { engineId: 'b', count: 3 },
    ])
    expect(statusCountsAreLowerBound(honesty)).toBe(false)
    expect(groupCountsAreLowerBound(honesty)).toBe(false)
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

  it('reports out-of-scope even while the same engine scan is truncated', () => {
    const honesty = deriveHonesty({
      a: { ok: true, dlqScan: 'truncated@500', outOfScopeDeadletters: 2 },
    })
    expect(honesty.truncatedScans).toEqual([{ engineId: 'a', marker: 'truncated@500' }])
    expect(honesty.outOfScope).toEqual([{ engineId: 'a', count: 2 }])
  })
})

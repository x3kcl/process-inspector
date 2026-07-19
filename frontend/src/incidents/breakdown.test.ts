import { describe, expect, it } from 'vitest'
import { engineSummary, flattenBreakdown } from './breakdown'

describe('flattenBreakdown', () => {
  it('folds engine → defKey:vN counts into rows ordered by total desc then engineId', () => {
    const counts = {
      'engine-b': { 'orderProcess:v2': 3 },
      'engine-a': { 'orderProcess:v1': 10, 'shipProcess:v1': 5 },
    }
    const rows = flattenBreakdown(counts)
    expect(rows.map((r) => r.engineId)).toEqual(['engine-a', 'engine-b'])
    expect(rows[0].total).toBe(15)
    expect(rows[1].total).toBe(3)
  })

  it('is empty for undefined/empty input', () => {
    expect(flattenBreakdown(undefined)).toEqual([])
    expect(flattenBreakdown({})).toEqual([])
  })
})

describe('engineSummary', () => {
  it('counts distinct engines and distinct definition keys across all engines', () => {
    const counts = {
      'engine-a': { 'orderProcess:v1': 10, 'shipProcess:v1': 5 },
      'engine-b': { 'orderProcess:v2': 3 },
    }
    expect(engineSummary(counts)).toEqual({ engineCount: 2, definitionCount: 2 })
  })

  it('is zero/zero for no scope', () => {
    expect(engineSummary(undefined)).toEqual({ engineCount: 0, definitionCount: 0 })
  })
})

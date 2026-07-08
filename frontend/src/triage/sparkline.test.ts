import { describe, expect, it } from 'vitest'
import type { TriageTrendResponse } from '../api/model'
import { globalLaneSeries, toPolyline, trendDirection } from './sparkline'

const series: TriageTrendResponse['series'] = [
  {
    engineId: 'engine-a',
    lane: 'FAILED',
    points: [
      { sampledAt: '2026-07-08T11:00:00Z', count: 3 },
      { sampledAt: '2026-07-08T11:01:00Z', count: 5 },
    ],
  },
  {
    engineId: 'engine-b',
    lane: 'FAILED',
    points: [
      { sampledAt: '2026-07-08T11:00:00Z', count: 1 },
      { sampledAt: '2026-07-08T11:01:00Z', count: 2 },
    ],
  },
  {
    engineId: 'engine-a',
    lane: 'ACTIVE',
    points: [{ sampledAt: '2026-07-08T11:00:00Z', count: 99 }],
  },
]

describe('globalLaneSeries', () => {
  it('sums the lane across engines by bucket, ascending, ignoring other lanes', () => {
    expect(globalLaneSeries(series, 'FAILED')).toEqual([
      { sampledAt: '2026-07-08T11:00:00Z', count: 4 },
      { sampledAt: '2026-07-08T11:01:00Z', count: 7 },
    ])
  })

  it('returns empty for a lane with no history', () => {
    expect(globalLaneSeries(series, 'SUSPENDED')).toEqual([])
    expect(globalLaneSeries(undefined, 'FAILED')).toEqual([])
  })
})

describe('toPolyline', () => {
  it('maps an ascending series bottom→top (y inverted)', () => {
    // 3 points over 100×20: min→y=20 (bottom), max→y=0 (top), evenly spaced x.
    expect(toPolyline([1, 2, 3], 100, 20)).toBe('0,20 50,10 100,0')
  })

  it('draws a flat series at mid-height, never pinned to an edge', () => {
    expect(toPolyline([5, 5, 5], 100, 20)).toBe('0,10 50,10 100,10')
  })

  it('centres a single point and yields nothing for empty', () => {
    expect(toPolyline([7], 100, 20)).toBe('50,10')
    expect(toPolyline([], 100, 20)).toBe('')
  })
})

describe('trendDirection', () => {
  it('reads first→last', () => {
    expect(trendDirection([1, 9])).toBe('up')
    expect(trendDirection([9, 1])).toBe('down')
    expect(trendDirection([4, 4])).toBe('flat')
    expect(trendDirection([7])).toBe('flat')
  })
})

import { describe, expect, it } from 'vitest'
import type { OccurrencePoint } from '../api/model'
import { timelineCoords, timelinePoints, timelinePolyline } from './timeline'

describe('timelinePoints', () => {
  it('normalizes, coalesces optional fields, and sorts ascending by time', () => {
    const series: OccurrencePoint[] = [
      { sampledAt: '2026-07-18T11:01:00Z', total: 5, truncated: true },
      { sampledAt: '2026-07-18T11:00:00Z' },
    ]
    expect(timelinePoints(series)).toEqual([
      { sampledAt: '2026-07-18T11:00:00Z', total: 0, truncated: false },
      { sampledAt: '2026-07-18T11:01:00Z', total: 5, truncated: true },
    ])
  })

  it('drops unplottable points with no sampledAt', () => {
    expect(timelinePoints([{ total: 3 }])).toEqual([])
  })

  it('is empty for undefined series', () => {
    expect(timelinePoints(undefined)).toEqual([])
  })
})

describe('timelineCoords', () => {
  it('scales an ascending series bottom→top (y inverted), like the small sparklines', () => {
    const points = timelinePoints([
      { sampledAt: 't1', total: 1 },
      { sampledAt: 't2', total: 2 },
      { sampledAt: 't3', total: 3 },
    ])
    const coords = timelineCoords(points, 100, 20)
    expect(coords.map((c) => [c.x, c.y])).toEqual([
      [0, 20],
      [50, 10],
      [100, 0],
    ])
  })

  it('draws a flat series at mid-height, never pinned to an edge', () => {
    const points = timelinePoints([
      { sampledAt: 't1', total: 5 },
      { sampledAt: 't2', total: 5 },
    ])
    expect(timelineCoords(points, 100, 20).map((c) => c.y)).toEqual([10, 10])
  })

  it('centres a single point and yields nothing for empty', () => {
    const one = timelinePoints([{ sampledAt: 't1', total: 7 }])
    expect(timelineCoords(one, 100, 20)).toEqual([
      { sampledAt: 't1', total: 7, truncated: false, x: 50, y: 10 },
    ])
    expect(timelineCoords([], 100, 20)).toEqual([])
  })

  it('carries the truncated flag through onto each coordinate', () => {
    const points = timelinePoints([
      { sampledAt: 't1', total: 1, truncated: true },
      { sampledAt: 't2', total: 2 },
    ])
    const coords = timelineCoords(points, 100, 20)
    expect(coords[0].truncated).toBe(true)
    expect(coords[1].truncated).toBe(false)
  })
})

describe('timelinePolyline', () => {
  it('joins coordinates into an SVG polyline points string', () => {
    const coords = timelineCoords(
      timelinePoints([
        { sampledAt: 't1', total: 1 },
        { sampledAt: 't2', total: 3 },
      ]),
      100,
      20,
    )
    expect(timelinePolyline(coords)).toBe('0,20 100,0')
  })

  it('is empty for no coordinates', () => {
    expect(timelinePolyline([])).toBe('')
  })
})

describe('occurrence gaps — the engine-down cycle renders honestly (S5 edge case)', () => {
  // INCIDENT-LEDGER.md §5: a down engine is ABSENT from the aggregation, so its cycles write
  // no occurrence row — the series arrives NON-CONTIGUOUS. The helper must carry exactly the
  // observed samples through: no interpolated point, no fabricated zero bridging the gap.
  it('never interpolates or zero-fills a missing bucket', () => {
    const observed = [
      { sampledAt: '2026-07-18T09:00:00Z', total: 6 },
      { sampledAt: '2026-07-18T09:01:00Z', total: 8 },
      // 09:02 and 09:03 missing — the engine was down; nothing was observed
      { sampledAt: '2026-07-18T09:04:00Z', total: 7 },
    ]
    const points = timelinePoints(observed)
    expect(points).toHaveLength(3) // one point per OBSERVATION — no synthetic points
    expect(points.map((p) => p.total)).toEqual([6, 8, 7]) // no fabricated zeros

    const coords = timelineCoords(points, 100, 20)
    expect(coords).toHaveLength(3)
    // the polyline has exactly one vertex per observation — a gap never mints a vertex
    expect(timelinePolyline(coords).split(' ')).toHaveLength(3)
    // and the y-scale spans the OBSERVED min..max (6..8) — a fabricated zero would have
    // stretched the scale to 0..8 and dragged every real point toward the top edge
    expect(coords.map((c) => c.y)).toEqual([20, 0, 10])
  })
})

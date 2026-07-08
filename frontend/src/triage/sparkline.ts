import type { TriageTrendResponse } from '../api/model'

export interface LanePoint {
  sampledAt: string
  count: number
}

/**
 * Collapse the per-engine trend series into ONE global-by-bucket series for a lane — the
 * Stage-0 tiles show global totals, so the sparkline under a tile is the global trend. All
 * engines in one sampler cycle share a bucket instant, so summing by {@code sampledAt} is exact.
 * Returns ascending-by-time; a lane with no history yields an empty array (no fabricated line).
 */
export function globalLaneSeries(series: TriageTrendResponse['series'], lane: string): LanePoint[] {
  const byBucket = new Map<string, number>()
  for (const s of series ?? []) {
    if (s.lane !== lane) continue
    for (const p of s.points ?? []) {
      byBucket.set(p.sampledAt ?? '', (byBucket.get(p.sampledAt ?? '') ?? 0) + (p.count ?? 0))
    }
  }
  return [...byBucket.entries()]
    .map(([sampledAt, count]) => ({ sampledAt, count }))
    .sort((a, b) => a.sampledAt.localeCompare(b.sampledAt))
}

/**
 * Map counts to an SVG polyline in a {@code [0..width] × [0..height]} box, y-inverted so a
 * higher count sits higher. Scales to the series' own min/max (relative movement, not absolute);
 * a flat series draws a mid-height line rather than pinning to an edge (which would read as zero).
 * Single point → a centred dot's coordinate; empty → ''.
 */
export function toPolyline(values: number[], width: number, height: number): string {
  if (values.length === 0) return ''
  const max = Math.max(...values)
  const min = Math.min(...values)
  const stepX = values.length === 1 ? 0 : width / (values.length - 1)
  return values
    .map((v, i) => {
      const x = values.length === 1 ? width / 2 : i * stepX
      const y = max === min ? height / 2 : height - ((v - min) / (max - min)) * height
      return `${String(round(x))},${String(round(y))}`
    })
    .join(' ')
}

/** Trend direction from first→last, for the accessible label (hue is only reinforcement). */
export function trendDirection(values: number[]): 'up' | 'down' | 'flat' {
  if (values.length < 2) return 'flat'
  const first = values[0]
  const last = values[values.length - 1]
  return last > first ? 'up' : last < first ? 'down' : 'flat'
}

function round(n: number): number {
  return Math.round(n * 100) / 100
}

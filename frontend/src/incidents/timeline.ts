// The incident detail's arrival-rate timeline (INCIDENT-LEDGER.md §8): an inline-SVG chart
// over the windowed occurrence series, EXTENDING triage/sparkline.ts's scaling math (same
// min/max-relative, y-inverted, "flat series draws mid-height" rules) rather than
// reimplementing it — the one difference is that a truncated sample is a FLOOR, not a dip
// (§5's honesty mandate), so every point keeps its own coordinate AND its truncated flag
// instead of collapsing straight to a polyline string like the small lane sparklines do.
import type { OccurrencePoint } from '../api/model'

export interface TimelinePoint {
  sampledAt: string
  total: number
  truncated: boolean
}

/** Normalizes + sorts the wire series ascending by time. Optional fields coalesce (§ gotcha:
 *  generated DTO fields are optional) — a point with no sampledAt is unplottable and dropped. */
export function timelinePoints(series: OccurrencePoint[] | undefined): TimelinePoint[] {
  return (series ?? [])
    .filter((point) => point.sampledAt !== undefined && point.sampledAt !== '')
    .map((point) => ({
      sampledAt: point.sampledAt ?? '',
      total: point.total ?? 0,
      truncated: point.truncated === true,
    }))
    .sort((a, b) => a.sampledAt.localeCompare(b.sampledAt))
}

export interface TimelineCoord extends TimelinePoint {
  x: number
  y: number
}

/**
 * Maps points into a `[0..width] × [0..height]` box (y-inverted — higher count sits higher),
 * scaled to the series' own min/max exactly like {@link toPolyline} in triage/sparkline.ts.
 * Kept as its own function (rather than importing that one) so line coordinates and marker
 * coordinates below are computed from the identical scale in one pass — no risk of the two
 * drifting apart if the sparkline's internal math changes independently.
 */
export function timelineCoords(
  points: TimelinePoint[],
  width: number,
  height: number,
): TimelineCoord[] {
  if (points.length === 0) return []
  const values = points.map((point) => point.total)
  const max = Math.max(...values)
  const min = Math.min(...values)
  const stepX = points.length === 1 ? 0 : width / (points.length - 1)
  return points.map((point, index) => {
    const x = points.length === 1 ? width / 2 : index * stepX
    const y = max === min ? height / 2 : height - ((point.total - min) / (max - min)) * height
    return { ...point, x: round(x), y: round(y) }
  })
}

/** The connecting line's SVG polyline attribute value (truncated points ride the same line —
 *  they only render a DIFFERENT marker on top, per {@link TimelineCoord.truncated}). */
export function timelinePolyline(coords: TimelineCoord[]): string {
  return coords.map((coord) => `${String(coord.x)},${String(coord.y)}`).join(' ')
}

function round(n: number): number {
  return Math.round(n * 100) / 100
}

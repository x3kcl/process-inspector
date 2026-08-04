// The incident detail's arrival-rate timeline (INCIDENT-LEDGER.md §8): an inline-SVG chart
// over the windowed occurrence series, EXTENDING triage/sparkline.ts's scaling math (same
// min/max-relative, y-inverted, "flat series draws mid-height" rules) rather than
// reimplementing it — the one difference is that a sample can be DISHONEST in two distinct ways,
// so every point keeps its own coordinate AND both honesty flags instead of collapsing straight
// to a polyline string like the small lane sparklines do:
//
//   * `truncated` — the failure-lane scan hit its cap, so the count is a FLOOR, not a level
//     (R-SEM-12, §5's honesty mandate).
//   * `blind` — `cycleComplete = false`: an engine was unreachable on the pass that wrote the
//     row (#302/V21), so the sample is missing that engine's members entirely. The chart draws a
//     DIP that is an OUTAGE, not a drain.
//
// The second flag is a review fix. V21 persists the marker and the ledger's own arrivals/spell
// readers already honour it, but `IncidentDetail.OccurrencePoint` did not carry it — so this
// chart rendered a blind-outage dip identically to a real recovery. That is the same class as
// the iron rule "never render a status derived from truncated data without the badge", and it
// is now marked exactly the way `truncated` already was.
import type { OccurrencePoint } from '../api/model'

export interface TimelinePoint {
  sampledAt: string
  total: number
  truncated: boolean
  blind: boolean
}

/** Normalizes + sorts the wire series ascending by time. Optional fields coalesce (§ gotcha:
 *  generated DTO fields are optional) — a point with no sampledAt is unplottable and dropped.
 *
 *  `blind` fails toward HONEST: an ABSENT `cycleComplete` (an older server, or a field the DTO
 *  did not yet carry) reads as blind rather than as complete, mirroring V21's own fail-closed
 *  backfill choice — asserting an observation nobody recorded is exactly the fabrication the
 *  marker exists to stop. */
export function timelinePoints(series: OccurrencePoint[] | undefined): TimelinePoint[] {
  return (series ?? [])
    .filter((point) => point.sampledAt !== undefined && point.sampledAt !== '')
    .map((point) => ({
      sampledAt: point.sampledAt ?? '',
      total: point.total ?? 0,
      truncated: point.truncated === true,
      blind: point.cycleComplete !== true,
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

/** The connecting line's SVG polyline attribute value (truncated and blind points ride the same
 *  line — they only render a DIFFERENT marker on top, per {@link TimelineCoord.truncated} /
 *  {@link TimelineCoord.blind}). */
export function timelinePolyline(coords: TimelineCoord[]): string {
  return coords.map((coord) => `${String(coord.x)},${String(coord.y)}`).join(' ')
}

function round(n: number): number {
  return Math.round(n * 100) / 100
}

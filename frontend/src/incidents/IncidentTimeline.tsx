// The incident detail's arrival-rate timeline (INCIDENT-LEDGER.md §8) — an inline-SVG chart
// over the windowed occurrence series, sized like a real chart (not a tile-sized sparkline:
// this is the ONE place per incident the series is fetched, so it earns the bigger canvas).
//
// A sample that cannot be taken at face value gets a marker of its own instead of a plain filled
// dot, plus one legend line each — never colour alone (SPEC §10a):
//   * TRUNCATED — a floor, not a dip (§5's honesty mandate): hollow/dashed.
//   * BLIND (`cycleComplete = false`, #302/V21) — an engine was unreachable when the sample was
//     written, so the dip it draws is an OUTAGE, not a drain. Marked as loudly as truncation is,
//     per the iron rule that a status derived from incomplete data never renders unbadged. This
//     is the review fix: V21 has persisted the marker since the ledger blind-cycle work, but the
//     detail DTO did not carry it, so this chart drew an outage identically to a recovery.
// A sample can be both; the blind marker wins the shape and the tooltip names both reasons.
import type { OccurrencePoint } from '../api/model'
import { timelineCoords, timelinePoints, timelinePolyline } from './timeline'

const WIDTH = 480
const HEIGHT = 90

interface Props {
  series: OccurrencePoint[] | undefined
}

export function IncidentTimeline({ series }: Props) {
  const points = timelinePoints(series)
  if (points.length < 2) {
    return <p className="strip-note">Not enough samples yet to draw a timeline.</p>
  }
  const coords = timelineCoords(points, WIDTH, HEIGHT)
  const hasTruncated = coords.some((coord) => coord.truncated)
  const hasBlind = coords.some((coord) => coord.blind)

  return (
    <div className="incident-timeline-wrap">
      <svg
        className="incident-timeline"
        viewBox={`0 0 ${String(WIDTH)} ${String(HEIGHT)}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={`Arrival-rate timeline, ${String(points.length)} samples`}
      >
        <polyline points={timelinePolyline(coords)} />
        {coords.map((coord) =>
          coord.blind ? (
            // A distinct SHAPE, not just a class: a blind sample is a different kind of
            // untrustworthy from a truncated one and must not be mistaken for it.
            <rect
              key={coord.sampledAt}
              className="incident-timeline-point-blind"
              x={coord.x - 3}
              y={coord.y - 3}
              width={6}
              height={6}
            >
              <title>
                {coord.sampledAt} — {String(coord.total)} (engine unreachable — this sample is
                missing that engine&rsquo;s members
                {coord.truncated ? '; the scan was also truncated' : ''})
              </title>
            </rect>
          ) : (
            <circle
              key={coord.sampledAt}
              className={
                coord.truncated ? 'incident-timeline-point-truncated' : 'incident-timeline-point'
              }
              cx={coord.x}
              cy={coord.y}
              r={coord.truncated ? 3 : 2}
            >
              <title>
                {coord.sampledAt} — {String(coord.total)}
                {coord.truncated ? ' (lower bound — scan truncated)' : ''}
              </title>
            </circle>
          ),
        )}
      </svg>
      {hasTruncated && (
        <p className="incident-timeline-legend">
          ○ hollow/dashed points are lower bounds — the failure-lane scan hit its cap on that
          sample; the true count may be higher, never lower.
        </p>
      )}
      {hasBlind && (
        <p className="incident-timeline-legend">
          □ square points were recorded while an engine was unreachable — they are missing that
          engine&rsquo;s members, so a dip there is an outage, not a drain.
        </p>
      )}
    </div>
  )
}

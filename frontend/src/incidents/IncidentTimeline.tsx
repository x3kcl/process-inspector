// The incident detail's arrival-rate timeline (INCIDENT-LEDGER.md §8) — an inline-SVG chart
// over the windowed occurrence series, sized like a real chart (not a tile-sized sparkline:
// this is the ONE place per incident the series is fetched, so it earns the bigger canvas).
// Truncated samples get a hollow/dashed marker instead of a filled dot — a floor, not a dip
// (§5's honesty mandate) — plus one legend line, never colour alone (SPEC §10a).
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
        {coords.map((coord) => (
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
        ))}
      </svg>
      {hasTruncated && (
        <p className="incident-timeline-legend">
          ○ hollow/dashed points are lower bounds — the failure-lane scan hit its cap on that
          sample; the true count may be higher, never lower.
        </p>
      )}
    </div>
  )
}

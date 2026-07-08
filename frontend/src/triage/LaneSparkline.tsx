import type { InstanceStatus, TriageTrendResponse } from '../api/model'
import { globalLaneSeries, toPolyline, trendDirection } from './sparkline'

const WIDTH = 64
const HEIGHT = 18

interface Props {
  trends: TriageTrendResponse | undefined
  lane: InstanceStatus
}

/**
 * A tiny inline-SVG trend line under a Stage-0 status tile (R-BAU-08), fed by the snapshot store.
 * Renders nothing until there are ≥2 points — a single sample is not a trend. Per the app's
 * "hue is redundant reinforcement" rule the meaning lives in the line's shape + the aria-label
 * (direction and endpoints), with the stroke colour only echoing the lane's chip.
 */
export function LaneSparkline({ trends, lane }: Props) {
  const points = trends ? globalLaneSeries(trends.series, lane) : []
  if (points.length < 2) return null

  const values = points.map((p) => p.count)
  const first = values[0]
  const last = values[values.length - 1]
  const dir = trendDirection(values)

  return (
    <svg
      className={`lane-sparkline lane-sparkline--${lane.toLowerCase()} lane-sparkline--${dir}`}
      width={WIDTH}
      height={HEIGHT}
      viewBox={`0 0 ${String(WIDTH)} ${String(HEIGHT)}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={`${lane} trend, ${String(points.length)} samples: ${String(first)} to ${String(last)} (${dir})`}
    >
      <polyline
        points={toPolyline(values, WIDTH, HEIGHT)}
        fill="none"
        strokeWidth={1.5}
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}

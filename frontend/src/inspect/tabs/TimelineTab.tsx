import { Link } from 'react-router'
import type { TimelineActivity } from '../../api/model'
import { formatDateTime, formatSeconds } from '../../lib/format'
import { useInstanceTimeline } from '../useInstanceQueries'

interface Props {
  engineId: string
  instanceId: string
}

/**
 * Historic activity instances as duration bars (SPEC §4), startTime ascending. Bars scale
 * to the instance's own time window; an unfinished activity runs to the window's edge and
 * says so. (Per-retry gaps are not reconstructable from Flowable history — not shown.)
 */
export default function TimelineTab({ engineId, instanceId }: Props) {
  const query = useInstanceTimeline(engineId, instanceId)

  if (query.isPending) return <div className="zero-state">Loading the activity timeline…</div>
  if (query.isError) {
    return (
      <div className="error-banner" role="alert">
        Timeline unavailable: {query.error.message}
      </div>
    )
  }
  const { activities = [], total = 0, truncated = false } = query.data
  if (activities.length === 0) {
    return (
      <div className="zero-state">
        No historic activity rows — the engine's history level may be dialed below activity.
      </div>
    )
  }
  const window = timeWindow(activities)
  return (
    <div className="timeline-tab">
      {truncated && (
        <p className="strip-note">
          Showing the first {activities.length} of {total} activities — the engine page cap
          truncated this list.
        </p>
      )}
      <div className="tl-rows">
        {activities.map((activity, index) => (
          <TimelineRow
            key={activity.id ?? `row-${String(index)}`}
            activity={activity}
            engineId={engineId}
            window={window}
          />
        ))}
      </div>
    </div>
  )
}

interface TimeWindow {
  start: number
  span: number
}

function timeWindow(activities: TimelineActivity[]): TimeWindow {
  let min = Number.POSITIVE_INFINITY
  let max = Number.NEGATIVE_INFINITY
  for (const activity of activities) {
    const start = parseTime(activity.startTime)
    if (start !== null) min = Math.min(min, start)
    const end = parseTime(activity.endTime)
    if (end !== null) max = Math.max(max, end)
  }
  // An all-open timeline (or unparsable dates) still renders: bars span "now".
  if (!Number.isFinite(min)) min = Date.now()
  max = Math.max(max, Date.now())
  return { start: min, span: Math.max(max - min, 1) }
}

function parseTime(iso: string | undefined): number | null {
  if (iso === undefined) return null
  const millis = new Date(iso).getTime()
  return Number.isNaN(millis) ? null : millis
}

function TimelineRow({
  activity,
  engineId,
  window,
}: {
  activity: TimelineActivity
  engineId: string
  window: TimeWindow
}) {
  const start = parseTime(activity.startTime) ?? window.start
  const end = parseTime(activity.endTime)
  const ongoing = end === null
  const left = ((start - window.start) / window.span) * 100
  const width = (((end ?? window.start + window.span) - start) / window.span) * 100
  const label = activity.activityName ?? activity.activityId ?? '?'
  const duration =
    activity.durationMs !== undefined
      ? formatSeconds(Math.round(activity.durationMs / 1000))
      : ongoing
        ? 'still running'
        : ''
  return (
    <div className={`tl-row${ongoing ? ' tl-ongoing' : ''}`}>
      <span className="tl-label" title={activity.activityId}>
        {label}
        <span className="tl-type value-muted"> {activity.activityType}</span>
        {activity.assignee !== undefined && (
          <span className="value-muted"> · {activity.assignee}</span>
        )}
        {activity.calledProcessInstanceId !== undefined && (
          <>
            {' '}
            <Link to={`/inspect/${engineId}/${activity.calledProcessInstanceId}`}>
              open child ↗
            </Link>
          </>
        )}
      </span>
      <span className="tl-track">
        <span
          className="tl-bar"
          style={{ left: `${String(left)}%`, width: `${String(Math.max(width, 0.5))}%` }}
          title={`${formatDateTime(activity.startTime)}${
            activity.endTime !== undefined ? ` → ${formatDateTime(activity.endTime)}` : ' → (open)'
          }`}
        />
      </span>
      <span className="tl-duration value-muted">{duration}</span>
    </div>
  )
}

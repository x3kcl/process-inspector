import { Link } from 'react-router'
import { formatDateTime, formatSeconds, useDisplayZone } from '../../lib/format'
import { useInstanceTimeline } from '../useInstanceQueries'
import { describeLiveState, flattenTimeline, type TimelineRowItem } from './timelineModel'

interface Props {
  engineId: string
  instanceId: string
}

/**
 * Historic activity instances as duration bars (SPEC §4), startTime ascending. A call-activity
 * row nests the called instance's own activities as an indented sub-lane; failing/unfinished
 * nodes carry a non-hue live-job badge (SPEC §10a), and a dead-lettered async node with no
 * history row is shown as a synthesized "phantom" bar. Bars scale to the instance's own time
 * window. (Per-retry gaps are not reconstructable from Flowable history — not shown.)
 */
export default function TimelineTab({ engineId, instanceId }: Props) {
  const query = useInstanceTimeline(engineId, instanceId)
  // R-UXQ-03: bar titles format timestamps as plain strings — re-render on the UTC toggle.
  useDisplayZone()

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
  const rows = flattenTimeline(activities)
  const window = timeWindow(rows)
  return (
    <div className="timeline-tab">
      {truncated && (
        <p className="strip-note">
          Showing the first {activities.length} of {total} top-level activities — the engine page
          cap truncated this list.
        </p>
      )}
      <div
        className="tl-rows"
        role="tree"
        aria-label="Activity timeline with call-activity sub-lanes"
      >
        {rows.map((row) => (
          <TimelineRowView key={row.key} row={row} engineId={engineId} window={window} />
        ))}
      </div>
    </div>
  )
}

interface TimeWindow {
  start: number
  span: number
}

function timeWindow(rows: TimelineRowItem[]): TimeWindow {
  let min = Number.POSITIVE_INFINITY
  let max = Number.NEGATIVE_INFINITY
  for (const row of rows) {
    if (row.kind === 'cap-warning') continue // synthesized rows carry no time
    const start = parseTime(row.activity.startTime)
    if (start !== null) min = Math.min(min, start)
    const end = parseTime(row.activity.endTime)
    if (end !== null) max = Math.max(max, end)
  }
  // An all-open (or phantom-only) timeline still renders: bars span "now".
  if (!Number.isFinite(min)) min = Date.now()
  max = Math.max(max, Date.now())
  return { start: min, span: Math.max(max - min, 1) }
}

function parseTime(iso: string | null | undefined): number | null {
  if (iso == null) return null
  const millis = new Date(iso).getTime()
  return Number.isNaN(millis) ? null : millis
}

function TimelineRowView({
  row,
  engineId,
  window,
}: {
  row: TimelineRowItem
  engineId: string
  window: TimeWindow
}) {
  if (row.kind === 'cap-warning') {
    return (
      <div
        className="tl-cap-warning"
        role="treeitem"
        aria-level={row.depth + 1}
        style={{ paddingLeft: `${String(row.depth * 16)}px` }}
      >
        <span aria-hidden="true">⚠</span>
        <span>
          Sub-tree truncated —{' '}
          {row.reason === 'render-depth'
            ? 'render depth limit reached'
            : 'depth / breadth / node limit reached'}
          . Server-capped to protect the browser and backend.
        </span>
      </div>
    )
  }
  return <TimelineActivityRow row={row} engineId={engineId} window={window} />
}

function TimelineActivityRow({
  row,
  engineId,
  window,
}: {
  row: Extract<TimelineRowItem, { kind: 'activity' | 'phantom' }>
  engineId: string
  window: TimeWindow
}) {
  const { activity, depth } = row
  const phantom = row.kind === 'phantom'
  const live = describeLiveState(activity.liveJobState)

  const start = parseTime(activity.startTime) ?? window.start
  const end = parseTime(activity.endTime)
  const ongoing = end === null
  const left = ((start - window.start) / window.span) * 100
  const width = (((end ?? window.start + window.span) - start) / window.span) * 100

  const label = activity.activityName ?? activity.activityId ?? '?'
  const duration = phantom
    ? 'timing unknown'
    : activity.durationMs !== undefined
      ? formatSeconds(Math.round(activity.durationMs / 1000))
      : ongoing
        ? 'still running'
        : ''
  const ariaLabel = [label, activity.activityType, live ? `— ${live.label}` : null]
    .filter(Boolean)
    .join(' ')

  return (
    <div
      className={`tl-row${ongoing && !phantom ? ' tl-ongoing' : ''}${phantom ? ' tl-phantom-row' : ''}`}
      role="treeitem"
      aria-level={depth + 1}
      aria-label={ariaLabel}
    >
      <span
        className={`tl-label${depth > 0 ? ' tl-label--nested' : ''}`}
        style={{ paddingLeft: `${String(depth * 16)}px` }}
        title={activity.activityId}
      >
        <span className="tl-name">{label}</span>
        <span className="tl-type value-muted"> {activity.activityType}</span>
        {activity.assignee !== undefined && (
          <span className="value-muted"> · {activity.assignee}</span>
        )}
        {live && (
          <span className={`tl-badge tl-badge--${live.label}`}>
            <span aria-hidden="true">{live.icon}</span> {live.label}
          </span>
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
        {phantom ? (
          <span
            className="tl-bar tl-bar--phantom"
            title="Synthesized from live job state — this activity has no history row (async rollback)"
          />
        ) : (
          <span
            className={`tl-bar${live ? ` ${live.barClass}` : ''}`}
            style={{ left: `${String(left)}%`, width: `${String(Math.max(width, 0.5))}%` }}
            title={`${formatDateTime(activity.startTime)}${
              activity.endTime !== undefined
                ? ` → ${formatDateTime(activity.endTime)}`
                : ' → (open)'
            }`}
          />
        )}
      </span>
      <span className="tl-duration value-muted">{duration}</span>
    </div>
  )
}

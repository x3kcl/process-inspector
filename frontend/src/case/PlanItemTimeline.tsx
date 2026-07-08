import type { CasePlanItems } from '../api/model'
import { describeLiveState, flattenPlanItems, phaseOf } from './planItemModel'

/**
 * The plan-item state-machine timeline (Case Inspector Phase 2) — the CMMN analog of the BPMN
 * activity timeline. Each plan item is a row: element + type + the engine `state` (with a coarse
 * phase pill) and a non-hue live-job badge (🛑 Failed / 🔁 Retrying). Stage children nest under
 * their parent stage (`role=tree` + `aria-level`).
 *
 * RUNTIME-ONLY on 6.8 (spike Q6): an ended case has no plan-item history source, so the backend
 * returns {@code available:false} + a reason — rendered honestly here, never a fabricated empty
 * timeline (the vitals header still tells the ended case's story).
 */
export function PlanItemTimeline({ data }: { data: CasePlanItems }) {
  if (!data.available) {
    return (
      <div className="plan-item-timeline plan-item-timeline--unavailable" role="note">
        <p>{data.unavailableReason ?? 'The plan-item timeline is not available for this case.'}</p>
      </div>
    )
  }

  const rows = flattenPlanItems(data.planItems ?? [])
  if (rows.length === 0) {
    return (
      <div className="plan-item-timeline" role="note">
        <p className="muted">No plan items on this case right now.</p>
      </div>
    )
  }

  return (
    <div className="plan-item-timeline">
      {data.truncated && (
        <p className="plan-item-timeline-truncated" role="note">
          Showing a bounded scan of this case&apos;s plan items — more lie past the cap.
        </p>
      )}
      <ul className="plan-item-list" role="tree" aria-label="CMMN plan items">
        {rows.map(({ item, depth }) => {
          const badge = describeLiveState(item.liveJobState)
          const phase = phaseOf(item.state)
          return (
            <li
              key={item.id ?? `${item.elementId ?? 'item'}-${String(depth)}`}
              className={`plan-item plan-item--depth-${String(Math.min(depth, 6))}`}
              role="treeitem"
              aria-level={depth + 1}
              style={{ marginInlineStart: `${String(depth * 1.25)}rem` }}
            >
              <div className="plan-item-head">
                <span className="plan-item-name">{item.name ?? item.elementId ?? '(unnamed)'}</span>
                {item.planItemDefinitionType && (
                  <span className="plan-item-type">{item.planItemDefinitionType}</span>
                )}
                <span className={`plan-item-state plan-item-state--${phase}`}>
                  {item.state ?? '—'}
                </span>
                {badge && (
                  <span className={`plan-item-badge ${badge.badgeClass}`}>
                    <span aria-hidden="true">{badge.glyph}</span> {badge.label}
                  </span>
                )}
              </div>
              <dl className="plan-item-meta">
                {item.lastStartedTime && (
                  <>
                    <dt>Started</dt>
                    <dd className="mono">{item.lastStartedTime}</dd>
                  </>
                )}
                {item.completedTime && (
                  <>
                    <dt>Completed</dt>
                    <dd className="mono">{item.completedTime}</dd>
                  </>
                )}
                {item.terminatedTime && (
                  <>
                    <dt>Terminated</dt>
                    <dd className="mono">{item.terminatedTime}</dd>
                  </>
                )}
              </dl>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

// v1.x #4 timeline sub-lanes (SPEC §4): the PURE rendering reducer for TimelineTab — the
// recursive backend tree (TimelineActivity.children) flattened to an ordered, depth-tagged
// list, plus the non-hue live-job-state presentation map (SPEC §10a). Kept framework-free so
// it is unit-tested in the node env (the repo's toolchain — component DOM is proven by the
// hermetic Playwright smoke, never RTL/jsdom).
import type { TimelineActivity } from '../../api/model'

/** The two live states the backend joins onto a failing/unfinished node. */
export type LiveJobState = NonNullable<TimelineActivity['liveJobState']>

/**
 * A defensive ceiling on render recursion. The backend already bounds the tree at depth 10,
 * but a malformed payload must never hang the browser thread (recursive guardrail) — past
 * this depth we stop and emit a cap warning instead of descending further.
 */
export const MAX_RENDER_DEPTH = 12

export type TimelineRowItem =
  | { kind: 'activity' | 'phantom'; key: string; depth: number; activity: TimelineActivity }
  | { kind: 'cap-warning'; key: string; depth: number; reason: 'server-cap' | 'render-depth' }

/**
 * A node synthesized purely from the live job lanes — a dead-lettered async task whose
 * history row rolled back — carries no times at all. A real (even unfinished) activity always
 * has a startTime; its absence is the phantom signal.
 */
export function isPhantom(activity: TimelineActivity): boolean {
  return activity.startTime == null && activity.endTime == null
}

/**
 * Pre-order DFS flatten: each activity emits a row before its children, so parents render
 * directly above their (indented) sub-lane. A capped node emits a cap-warning row at the end
 * of its children block. Depth-guarded so a pathological payload cannot recurse without bound.
 */
export function flattenTimeline(activities: readonly TimelineActivity[]): TimelineRowItem[] {
  const rows: TimelineRowItem[] = []

  const walk = (list: readonly TimelineActivity[], depth: number, path: string): void => {
    if (depth > MAX_RENDER_DEPTH) {
      rows.push({ kind: 'cap-warning', key: `${path}~depth`, depth, reason: 'render-depth' })
      return
    }
    list.forEach((activity, index) => {
      const key = `${path}.${String(index)}:${activity.id ?? activity.activityId ?? 'node'}`
      rows.push({ kind: isPhantom(activity) ? 'phantom' : 'activity', key, depth, activity })

      const children = activity.children ?? []
      if (children.length > 0) walk(children, depth + 1, key)
      if (activity.isCapped) {
        rows.push({
          kind: 'cap-warning',
          key: `${key}~cap`,
          depth: depth + 1,
          reason: 'server-cap',
        })
      }
    })
  }

  walk(activities, 0, 'root')
  return rows
}

/** Non-hue presentation for a live job state — the icon + text carry meaning; color is only reinforcement. */
export interface LiveStatePresentation {
  /** A shape/glyph, distinct per state (SPEC §10a — never color-only). */
  icon: string
  /** The literal state word — the primary, colorblind-safe carrier. */
  label: string
  /** The bar-fill class: a distinct PATTERN per state (hatch vs dashed), not just a hue. */
  barClass: string
}

export function describeLiveState(
  state: TimelineActivity['liveJobState'],
): LiveStatePresentation | null {
  switch (state) {
    case 'FAILED':
      return { icon: '🛑', label: 'FAILED', barClass: 'tl-bar--failed' }
    case 'RETRYING':
      return { icon: '⚠', label: 'RETRYING', barClass: 'tl-bar--retrying' }
    default:
      return null
  }
}

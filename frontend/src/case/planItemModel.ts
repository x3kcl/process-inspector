// Pure model for the CMMN plan-item state-machine timeline (Case Inspector Phase 2). No React,
// no DOM — the flatten/nesting/state-derivation logic is unit-tested here (repo doctrine: vitest
// runs env `node`, so the testable logic lives apart from the component).
import type { CasePlanItem, CmmnLiveJobState } from '../api/model'

/** A flattened plan item plus its nesting depth (a child of a stage is one level deeper). */
export interface PlanItemRow {
  item: CasePlanItem
  depth: number
}

// A malformed payload (a cycle in stageInstanceId) must never hang the browser.
const MAX_RENDER_DEPTH = 12

/**
 * Flattens the plan items into a pre-order (depth-tagged) list, nesting each under its parent
 * stage (a child's `stageInstanceId` == the parent stage plan item's `id`). Siblings are ordered
 * by `createTime` (the engine's own order). A plan item whose `stageInstanceId` points at no known
 * plan item is treated as a root (honest — never dropped). Cycle- and depth-guarded.
 */
export function flattenPlanItems(planItems: CasePlanItem[]): PlanItemRow[] {
  const byId = new Map<string, CasePlanItem>()
  for (const item of planItems) {
    if (item.id != null) byId.set(item.id, item)
  }

  const childrenOf = new Map<string | null, CasePlanItem[]>()
  for (const item of planItems) {
    const parentId =
      item.stageInstanceId != null && byId.has(item.stageInstanceId) ? item.stageInstanceId : null
    const bucket = childrenOf.get(parentId) ?? []
    bucket.push(item)
    childrenOf.set(parentId, bucket)
  }
  for (const bucket of childrenOf.values()) {
    bucket.sort((a, b) => (a.createTime ?? '').localeCompare(b.createTime ?? ''))
  }

  const rows: PlanItemRow[] = []
  const visited = new Set<string>()
  const walk = (parentId: string | null, depth: number) => {
    if (depth > MAX_RENDER_DEPTH) return
    for (const item of childrenOf.get(parentId) ?? []) {
      if (item.id != null && visited.has(item.id)) continue
      if (item.id != null) visited.add(item.id)
      rows.push({ item, depth })
      if (item.id != null) walk(item.id, depth + 1)
    }
  }
  walk(null, 0)

  // Any item unreachable from a root (e.g. a stageInstanceId cycle where neither node is a root)
  // is surfaced at depth 0 rather than silently dropped — honesty over a tidy tree.
  for (const item of planItems) {
    if (item.id != null && !visited.has(item.id)) {
      visited.add(item.id)
      rows.push({ item, depth: 0 })
      walk(item.id, 1)
    }
  }
  return rows
}

/** The live job annotation as a non-hue badge (SPEC §10a: pattern + text, never color alone). */
export function describeLiveState(
  liveJobState: CmmnLiveJobState | null | undefined,
): { label: string; glyph: string; badgeClass: string } | null {
  if (liveJobState === 'FAILED') {
    return { label: 'Failed', glyph: '🛑', badgeClass: 'plan-item-badge--failed' }
  }
  if (liveJobState === 'RETRYING') {
    return { label: 'Retrying', glyph: '🔁', badgeClass: 'plan-item-badge--retrying' }
  }
  return null
}

export type PlanItemPhase = 'active' | 'ended' | 'available'

/**
 * The coarse lifecycle phase for the state pill: `active` (in play), `ended` (completed /
 * terminated / occurred / exited), or `available` (waiting to be enabled). Drives styling only;
 * the exact engine `state` is always shown verbatim next to it.
 */
export function phaseOf(state: string | null | undefined): PlanItemPhase {
  switch (state) {
    case 'active':
    case 'async-active':
    case 'enabled':
      return 'active'
    case 'completed':
    case 'terminated':
    case 'occurred':
    case 'exited':
      return 'ended'
    default:
      return 'available'
  }
}

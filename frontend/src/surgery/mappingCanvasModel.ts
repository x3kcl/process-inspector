// Click-to-map model for the dual-canvas mapping editor (#498). Pure functions, so the
// fan rules and the finding overlay are unit-tested without bpmn-js (which needs a real DOM).
import { addPair, compileMappings } from './mappingRules'
import type { ActivityPair } from './mappingRules'

export type AddPairResult = { ok: true; pairs: ActivityPair[] } | { ok: false; reason: string }

/**
 * Add one source→target pair, refusing it (pair list unchanged) when the result would no
 * longer compile — i.e. it would join a fan-out and a fan-in into a many-to-many component.
 * A pair list that is ALREADY uncompilable (table mode lets it happen, with the alert) is
 * not made a reason to block an unrelated pair: only a newly broken list is refused.
 */
export function tryAddPair(
  pairs: readonly ActivityPair[],
  fromActivityId: string,
  toActivityId: string,
): AddPairResult {
  const next = addPair(pairs, fromActivityId, toActivityId)
  const after = compileMappings(next)
  if (!after.ok && compileMappings(pairs).ok) return { ok: false, reason: after.reason }
  return { ok: true, pairs: next }
}

export interface FindingLike {
  activityId?: string | null
  severity?: string
}

/** Activity ids to mark on the source canvas; a blocker wins over a warning on one id. */
export function findingMarkerIds(findings: readonly FindingLike[]): {
  blocker: string[]
  warning: string[]
} {
  const blocker = new Set<string>()
  const warning = new Set<string>()
  for (const finding of findings) {
    const id = finding.activityId
    if (typeof id !== 'string' || id === '') continue
    // An unrecognised severity (a taxonomy bump ahead of this build) is marked as a
    // warning, like MigrateModal's callouts: fail toward visible. Only INFO is unmarked.
    if (finding.severity === 'BLOCKER_ADVICE') blocker.add(id)
    else if (finding.severity !== 'INFO') warning.add(id)
  }
  return { blocker: [...blocker], warning: [...warning].filter((id) => !blocker.has(id)) }
}

/**
 * Ids carrying the "mapped" marker on each canvas. Selection sync: with a source selected,
 * the target canvas marks only that source's targets; otherwise every mapped target.
 */
export function mappedIds(
  pairs: readonly ActivityPair[],
  selectedFrom: string,
): { sources: string[]; targets: string[] } {
  const sources = new Set<string>()
  const targets = new Set<string>()
  for (const pair of pairs) {
    sources.add(pair.fromActivityId)
    if (selectedFrom === '' || pair.fromActivityId === selectedFrom) targets.add(pair.toActivityId)
  }
  return { sources: [...sources], targets: [...targets] }
}

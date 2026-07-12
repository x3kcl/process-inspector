// Pure view helpers for the registry admin surface (kept out of the component so they're
// unit-tested without a DOM, matching the repo's logic-test convention).
import type { EngineWriteOutcome } from './adminEngines'

/**
 * The human notice after a dangerous registry write (docs/REGISTRY-CRUD.md §9, R-SAFE-08, #91). A
 * `proposed` outcome names the concrete next move + the computed eligible-approver set; when that
 * set is empty it says so and points at the recovery move — never a bare four-eyes prompt that rots
 * as "denied". Mirrors `accessView.ts#outcomeNotice` (the IdP four-eyes precedent).
 */
export function engineOutcomeNotice(o: EngineWriteOutcome): string {
  if (o.status === 'proposed') {
    const eligible = (o.eligibleApproverGroups ?? []).filter((g) => g !== '')
    const who =
      eligible.length > 0
        ? `Eligible approver group(s): ${eligible.join(', ')}.`
        : 'No eligible approver — every REGISTRY_ADMIN shares your group(s); add a second independent REGISTRY_ADMIN.'
    return `Proposed — this is a dangerous registry write, so a second independent REGISTRY_ADMIN must approve. ${who} See the pending proposals below.`
  }
  return `Applied: ${o.summary ?? 'change'}.`
}

// Pure view helpers for the access-admin surface (kept out of the component so they're unit-tested
// without a DOM, matching the repo's logic-test convention).
import type { FleetView, LadderView, Outcome } from './accessAdmin'

/** Distinct, sorted group names across ladder + fleet grants (drives the grouped table). */
export function distinctGroups(ladder: LadderView[], fleet: FleetView[]): string[] {
  const set = new Set<string>()
  for (const r of ladder) if (r.group !== undefined && r.group !== '') set.add(r.group)
  for (const r of fleet) if (r.group !== undefined && r.group !== '') set.add(r.group)
  return [...set].sort()
}

/**
 * The human notice after a write (IDP-SECURITY.md §12, ⚠️ UX). A `proposed` outcome names the
 * concrete next move + the computed eligible-approver set; when that set is empty it says so and
 * points at the file-pin recovery — never a bare four-eyes prompt that rots as "denied".
 */
export function outcomeNotice(o: Outcome): string {
  if (o.status === 'proposed') {
    const eligible = (o.eligibleApproverGroups ?? []).filter((g) => g !== '')
    const who =
      eligible.length > 0
        ? `Eligible approver group(s): ${eligible.join(', ')}.`
        : 'No eligible approver — recover via the file-pin (mapping-source: file), see RUNBOOK.'
    return `Proposed — this widens access, so a second independent ACCESS_ADMIN must approve. ${who} See the pending proposals below.`
  }
  return `Applied: ${o.summary ?? 'change'}.`
}

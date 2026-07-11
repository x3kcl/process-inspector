// Curated LEAK views (SPEC §4 Stage 0, R-BAU-02): the slow leaks that never enter a failure
// lane — long-RUNNING and long-SUSPENDED process instances, grouped per definition. Like the
// SYSTEM views (systemViews.ts) a leak view is nothing but a named URL search string replaying
// Stage-1 state; here the search is parameterized by ONE definition key and the age boundary
// (startedBefore) so the landing can render "vacationRequest: 212 > 30d" as a real deep link.
//
// Honesty rule R-SEM-05 — no view may promise what the REST API cannot evaluate: Flowable
// records NO suspension timestamp, so the "Suspended · started > 7 days ago" view is defined
// against startTime (currently suspended AND startedBefore now−7d), NEVER time-since-suspension,
// exactly as the shipped "Suspended > 24h (by start time)" system view. Age = now − startTime
// via the startedBefore predicate throughout.
import type { InstanceStatus } from '../api/model'
import { encodeSearch } from '../search/urlState'

/** The three curated windows (R-BAU-02); ids match the backend {@code LeakViewsResponse} keys. */
export type LeakWindowId = 'activeOver30d' | 'activeOver90d' | 'suspendedStartedOver7d'

export interface LeakView {
  id: LeakWindowId
  /** R-SEM-05 predicate-honest label — never implies a duration the REST API cannot measure. */
  label: string
  /** Compact chip caption shown beside the per-definition count ("> 30d"). */
  short: string
  status: Extract<InstanceStatus, 'ACTIVE' | 'SUSPENDED'>
  /** Honesty footnote (chip tooltip); present when the label needs a caveat (suspended). */
  note?: string
  /**
   * The Stage-1 URL search replaying this view for ONE definition at a given age boundary.
   * {@code startedBefore} is supplied by the caller (the backend's exact window instant) so the
   * landing count and the link it navigates to share the identical predicate.
   */
  search: (definitionKey: string, startedBefore: string) => string
}

const leakSearch =
  (status: 'ACTIVE' | 'SUSPENDED') => (definitionKey: string, startedBefore: string) =>
    encodeSearch({
      processDefinitionKey: definitionKey,
      statuses: [status],
      startedBefore,
      sortBy: 'startTime',
    }).toString()

export const LEAK_VIEWS: readonly LeakView[] = [
  {
    id: 'activeOver30d',
    label: 'Active · started > 30 days ago',
    short: '> 30d',
    status: 'ACTIVE',
    search: leakSearch('ACTIVE'),
  },
  {
    id: 'activeOver90d',
    label: 'Active · started > 90 days ago',
    short: '> 90d',
    status: 'ACTIVE',
    search: leakSearch('ACTIVE'),
  },
  {
    id: 'suspendedStartedOver7d',
    label: 'Suspended · started > 7 days ago',
    // "started" stays in the compact caption too, so the visible chip can never be misread as
    // "suspended for 7 days" (R-SEM-05) — the count includes an instance suspended 5 min ago
    // that STARTED > 7d ago, and the caption says exactly that.
    short: 'suspended · started > 7d',
    status: 'SUSPENDED',
    // R-SEM-05: Flowable records no suspension timestamp — this view CANNOT mean "suspended
    // for more than 7 days". It is "currently suspended AND started more than 7 days ago",
    // the same honest scoping the shipped "Suspended > 24h (by start time)" system view uses.
    note: 'Flowable records no suspension timestamp — this is "currently suspended AND started more than 7 days ago", never time-since-suspension',
    search: leakSearch('SUSPENDED'),
  },
]

export function leakViewById(id: LeakWindowId): LeakView {
  const found = LEAK_VIEWS.find((view) => view.id === id)
  if (found === undefined) throw new Error(`missing leak view ${id}`)
  return found
}

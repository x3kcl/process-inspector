// Curated system views (SPEC §4 Stage 0). Honesty rule R-SEM-05: no system view ships
// whose predicate the REST API cannot evaluate faithfully. Flowable records no suspension
// timestamp, so "Suspended > 24h" is honestly "currently suspended AND started > 24h ago"
// (startedBefore) — its name and note say so. "Failed in the last hour" rides the DLQ job
// createTime (failedAfter), never the instance start time (SPEC §8 timeframe rule).
import { encodeSearch } from '../search/urlState'

export interface SystemView {
  id: string
  name: string
  /** Honest-predicate footnote, rendered as the chip tooltip. */
  note?: string
  /** Relative time windows must be materialized at click/render time. */
  search: (now: Date) => string
}

/**
 * Floor to the minute: a view's URL stays byte-identical for a minute, so the chip that
 * produced the current URL highlights as active (and stops matching once the window it
 * named has genuinely moved on).
 */
export function minuteFloor(now: Date): Date {
  const floored = new Date(now.getTime())
  floored.setSeconds(0, 0)
  return floored
}

function hoursBefore(now: Date, hours: number): string {
  return new Date(minuteFloor(now).getTime() - hours * 3_600_000).toISOString()
}

export const SYSTEM_VIEWS: readonly SystemView[] = [
  {
    id: 'sys-failed-all',
    name: 'Failed (all engines)',
    search: () => encodeSearch({ statuses: ['FAILED'], sortBy: 'failureTime' }).toString(),
  },
  {
    id: 'sys-failed-1h',
    name: 'Failed in the last hour',
    note: 'failure time = dead-letter job createTime — independent of when the instance started',
    search: (now) =>
      encodeSearch({
        statuses: ['FAILED'],
        failureTimeAfter: hoursBefore(now, 1),
        sortBy: 'failureTime',
      }).toString(),
  },
  {
    id: 'sys-suspended-24h',
    name: 'Suspended > 24h (by start time)',
    note: 'Flowable records no suspension timestamp — this view is "currently suspended AND started more than 24h ago"',
    search: (now) =>
      encodeSearch({ statuses: ['SUSPENDED'], startedBefore: hoursBefore(now, 24) }).toString(),
  },
  {
    id: 'sys-started-1h',
    name: 'Started in the last hour',
    search: (now) => encodeSearch({ startedAfter: hoursBefore(now, 1) }).toString(),
  },
]

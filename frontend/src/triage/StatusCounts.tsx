import { Link } from 'react-router'
import type { InstanceStatus } from '../api/model'
import { ALL_STATUSES, isInstanceStatus } from '../api/model'
import { formatCount } from '../lib/format'
import { statusDrillParams } from './drill'

interface Props {
  counts: Record<string, number> | undefined
  /** True when any engine is missing from the aggregate — every tile becomes "≥ n". */
  lowerBound: boolean
}

/**
 * Stage 0 global status counts (query totals, never row fetches — computed server-side).
 * Each tile drills through to a pre-filtered Stage 1 search on that status (R-SEM-12).
 */
export function StatusCounts({ counts, lowerBound }: Props) {
  if (counts === undefined) return null
  const known = ALL_STATUSES.filter((status) => status in counts)
  // Statuses the server may add later still render — just without a canonical slot.
  const extra = Object.keys(counts)
    .filter((key) => !isInstanceStatus(key))
    .sort()
  return (
    <section className="status-summary" aria-label="Instance counts by status">
      {known.map((status) => (
        <StatusTile
          key={status}
          status={status}
          count={counts[status] ?? 0}
          lowerBound={lowerBound}
        />
      ))}
      {extra.map((status) => (
        <span key={status} className="status-tile">
          <span className="status-chip">{status}</span>
          <Count value={counts[status] ?? 0} lowerBound={lowerBound} />
        </span>
      ))}
    </section>
  )
}

function StatusTile({
  status,
  count,
  lowerBound,
}: {
  status: InstanceStatus
  count: number
  lowerBound: boolean
}) {
  return (
    <Link
      className="status-tile status-tile-link"
      to={`/search?${statusDrillParams(status)}`}
      title={`Search all ${status} instances`}
    >
      <span className={`status-chip ${status.toLowerCase()}`}>{status}</span>
      <Count value={count} lowerBound={lowerBound} />
    </Link>
  )
}

function Count({ value, lowerBound }: { value: number; lowerBound: boolean }) {
  return (
    <span
      className="tile-count"
      title={
        lowerBound ? 'lower bound — at least one engine is missing from this count' : undefined
      }
    >
      {lowerBound ? '≥ ' : ''}
      {formatCount(value)}
    </span>
  )
}

import type { InstanceStatus, InstanceStatusFlags } from '../api/model'
import { secondaryBadges } from '../search/partials'

interface Props {
  status: InstanceStatus | undefined
  flags?: InstanceStatusFlags
}

/**
 * Primary chip (precedence ladder, ARCH §2.3) + the secondary badges for flag collisions
 * the ladder hides (e.g. "FAILED · suspended"). Text at every density (SPEC §10a).
 */
export function StatusChip({ status, flags }: Props) {
  if (status === undefined) return null
  return (
    <span className="status-cell">
      <span className={`status-chip ${status.toLowerCase()}`}>{status}</span>
      {secondaryBadges(status, flags).map((badge) => (
        <span key={badge} className="status-badge">
          {badge}
        </span>
      ))}
    </span>
  )
}

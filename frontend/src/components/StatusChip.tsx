import { useState } from 'react'
import type { InstanceStatus, InstanceStatusFlags } from '../api/model'
import { secondaryBadges } from '../search/partials'
import { StatusEvidenceModal } from './StatusEvidenceModal'

interface Props {
  status: InstanceStatus | undefined
  flags?: InstanceStatusFlags
  /**
   * Status honesty (#118/#105): when set, this ENDED instance was TERMINATED/deleted, not a normal
   * completion. Flowable ends both with an endTime so the status enum stays COMPLETED (search facets
   * must not churn), but the detail chip must not lie — with a reason present it renders TERMINATED
   * (the reason in its tooltip) instead of COMPLETED.
   */
  terminationReason?: string
  /**
   * When BOTH ids are present the chip becomes interactive (R-L3-01): clicking it opens the
   * "Explain this status" evidence view. Omit them and the chip renders exactly as before — a
   * plain, non-interactive label (search-preview and other read-only contexts).
   */
  engineId?: string
  instanceId?: string
}

/**
 * Primary chip (precedence ladder, ARCH §2.3) + the secondary badges for flag collisions
 * the ladder hides (e.g. "FAILED · suspended"). Text at every density (SPEC §10a).
 *
 * Interactive when given an instance identity: the chip is a button offering "Explain this
 * status" — the falsifiable, re-derived derivation (R-L3-01, SPEC §3), which is also where the
 * grid-parent-ACTIVE vs detail-FAILED-"in subprocess" contradiction becomes explainable.
 */
export function StatusChip({ status, flags, terminationReason, engineId, instanceId }: Props) {
  const [open, setOpen] = useState(false)
  if (status === undefined) return null
  const interactive = engineId !== undefined && instanceId !== undefined
  // #118/#105: an ended-but-terminated instance must not read COMPLETED.
  const terminated = terminationReason !== undefined && terminationReason !== ''
  const label = terminated ? 'TERMINATED' : status
  const chipClass = terminated ? 'terminated' : status.toLowerCase()
  const chip = (
    <span
      className={`status-chip ${chipClass}`}
      title={
        terminated ? `Terminated — ${terminationReason} (did not complete normally)` : undefined
      }
    >
      {label}
    </span>
  )
  const badges = secondaryBadges(status, flags).map((badge) => (
    <span key={badge} className="status-badge">
      {badge}
    </span>
  ))

  if (!interactive) {
    return (
      <span className="status-cell">
        {chip}
        {badges}
      </span>
    )
  }

  return (
    <span className="status-cell">
      <button
        type="button"
        className="status-chip-button"
        title="Explain this status"
        aria-label={`${label} — explain this status`}
        onClick={(event) => {
          // In an AG Grid cell the click must not also select/open the row.
          event.stopPropagation()
          setOpen(true)
        }}
      >
        {chip}
      </button>
      {badges}
      {open && (
        <StatusEvidenceModal
          engineId={engineId}
          instanceId={instanceId}
          onClose={() => {
            setOpen(false)
          }}
        />
      )}
    </span>
  )
}

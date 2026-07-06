// The bulk action bar (SPEC §7): pinned above the grid footer while rows are selected.
// Offers exactly the Intersection-Rule verbs (disabled-with-reason), badges protected
// exclusions, and routes every click through the submit modal — which doubles as the
// partial-result acknowledgment gate ("billing-prod excluded — proceed anyway?").
import { useMemo, useState } from 'react'
import type { ProcessInstanceRow } from '../api/model'
import { useSubmitBulk } from '../api/bulk'
import type { BulkTarget } from '../api/bulk'
import { problemBanner } from '../actions/problem'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'
import type { EngineFailure } from '../search/partials'
import { perEngineSplit, planSelection } from './intersection'
import type { BulkVerbOffer } from './intersection'

interface Props {
  selected: ProcessInstanceRow[]
  /** Engines that failed this search — bulk over a partial set needs explicit ack. */
  failedEngines: EngineFailure[]
  /** True when scans were truncated (10k-DLQ hit etc.) — same acknowledgment gate. */
  truncated: boolean
  onSubmitted: () => void
}

export function BulkBar({ selected, failedEngines, truncated, onSubmitted }: Props) {
  const [modalVerb, setModalVerb] = useState<BulkVerbOffer | null>(null)
  const plan = useMemo(() => planSelection(selected), [selected])

  if (selected.length === 0) return null

  return (
    <div className="bulk-bar" role="toolbar" aria-label="bulk actions">
      <span className="bulk-count">
        {selected.length} selected
        {plan.protectedExcluded > 0 && (
          <span
            className="status-badge"
            title="protected instances (R-SAFE-05) are excluded from bulk automatically — they stay listed in the job report as skipped"
          >
            {plan.protectedExcluded} protected instance{plan.protectedExcluded === 1 ? '' : 's'}{' '}
            excluded
          </span>
        )}
        {plan.protectionUnknown > 0 && (
          <span
            className="status-badge"
            title="the protection registry was unreachable when this page loaded — the BFF guard still refuses protected targets per item"
          >
            protection unknown for {plan.protectionUnknown}
          </span>
        )}
      </span>
      {plan.offers.map((offer) => (
        <button
          key={offer.verb}
          type="button"
          className="copy-btn action-btn"
          disabled={!offer.enabled}
          title={offer.enabled ? offer.plain : offer.reason}
          onClick={() => {
            setModalVerb(offer)
          }}
        >
          {offer.label}
        </button>
      ))}
      {modalVerb !== null && (
        <BulkSubmitModal
          offer={modalVerb}
          targets={plan.targets}
          failedEngines={failedEngines}
          truncated={truncated}
          onClose={() => {
            setModalVerb(null)
          }}
          onSubmitted={() => {
            setModalVerb(null)
            onSubmitted()
          }}
        />
      )}
    </div>
  )
}

function BulkSubmitModal({
  offer,
  targets,
  failedEngines,
  truncated,
  onClose,
  onSubmitted,
}: {
  offer: BulkVerbOffer
  targets: ProcessInstanceRow[]
  failedEngines: EngineFailure[]
  truncated: boolean
  onClose: () => void
  onSubmitted: () => void
}) {
  const toast = useToast()
  const submit = useSubmitBulk()
  const [reason, setReason] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [listOpen, setListOpen] = useState(false)
  const split = perEngineSplit(targets)
  // SPEC §7: bulk over a partial result set is BLOCKED until explicitly acknowledged.
  const partial = failedEngines.length > 0 || truncated
  const reasonOk = reason.trim() === '' || reason.trim().length >= 10
  const canSubmit = !submit.isPending && reasonOk && (!partial || acknowledged)

  const dispatch = () => {
    const items: BulkTarget[] = targets.map((row) => ({
      engineId: row.engineId ?? '',
      instanceId: row.processInstanceId ?? '',
    }))
    submit.mutate(
      { verb: offer.verb, reason: reason.trim() === '' ? undefined : reason.trim(), items },
      {
        onSuccess: (job) => {
          toast({
            kind: 'success',
            text: `Bulk ${offer.label.toLowerCase()} submitted — ${String(job.totalItems ?? items.length)} items, tracked as job ${(job.id ?? '').slice(0, 8)}…. Progress in the operations drawer.`,
          })
          onSubmitted()
        },
      },
    )
  }

  return (
    <ModalShell
      title={`${offer.label} — ${offer.plain}`}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="primary"
            disabled={!canSubmit}
            title={
              partial && !acknowledged
                ? 'acknowledge the partial result set first'
                : !reasonOk
                  ? 'a reason, when given, must be at least 10 characters'
                  : undefined
            }
            onClick={dispatch}
          >
            {submit.isPending
              ? 'Submitting…'
              : `${offer.label} — ${String(targets.length)} instance${targets.length === 1 ? '' : 's'}`}
          </button>
        </>
      }
    >
      {/* Scope enumeration: count, per-engine split, expandable list (SPEC §7). */}
      <p className="bulk-scope">
        {targets.length} instance{targets.length === 1 ? '' : 's'} across {split.length} engine
        {split.length === 1 ? '' : 's'}:{' '}
        {split.map(([engineId, count]) => `${engineId} (${String(count)})`).join(' · ')}
      </p>
      <details
        open={listOpen}
        onToggle={(e) => {
          setListOpen((e.target as HTMLDetailsElement).open)
        }}
      >
        <summary>show all {targets.length} composite IDs</summary>
        <ul className="cascade-list">
          {targets.map((row) => (
            <li key={row.compositeId ?? row.processInstanceId}>
              <code>{row.compositeId}</code>
              {typeof row.businessKey === 'string' &&
                row.businessKey !== '' &&
                ` — ${row.businessKey}`}
            </li>
          ))}
        </ul>
      </details>

      <p className="strip-note">
        Executed per item — no cross-engine transaction. Each item runs the full guard chain and
        writes its own audit row; partial failure is reported per item in the operations drawer,
        never rolled back.
      </p>

      {partial && (
        <div className="callout callout-amber" role="alert">
          <p className="cascade-warning">This selection comes from a PARTIAL result set:</p>
          <ul className="cascade-list">
            {failedEngines.map((failure) => (
              <li key={failure.engineId}>
                <code>{failure.engineId}</code> excluded — {failure.error}
              </li>
            ))}
            {truncated && <li>result scans were truncated — matching instances may be missing</li>}
          </ul>
          <label className="ack-line">
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={(e) => {
                setAcknowledged(e.target.checked)
              }}
            />
            Proceed anyway — I understand instances outside this result set are NOT included.
          </label>
        </div>
      )}

      <label className="modal-field">
        Reason (optional, ≥10 chars when given — lands on every item&apos;s audit row)
        <textarea
          value={reason}
          rows={2}
          maxLength={2000}
          onChange={(e) => {
            setReason(e.target.value)
          }}
        />
      </label>

      {submit.error !== null && (
        <div className="error-banner" role="alert">
          {problemBanner(submit.error.problem)}
        </div>
      )}
    </ModalShell>
  )
}

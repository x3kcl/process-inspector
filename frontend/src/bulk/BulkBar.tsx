// The bulk action bar (SPEC §7): pinned above the grid footer while rows are selected.
// Offers exactly the Intersection-Rule verbs (disabled-with-reason), badges protected
// exclusions, and routes every click through the submit modal — which doubles as the
// partial-result acknowledgment gate ("billing-prod excluded — proceed anyway?").
//
// v1.x #2 adds the FILTER SCOPE: selecting every visible row (or asking outright) flips
// the bar from "these N checkboxes" to "everything matching the current filter" — the
// scope is then the CRITERIA, re-resolved server-side at execution time, never a row list.
import { useEffect, useMemo, useState } from 'react'
import type { EngineDto, ProcessInstanceRow, SearchRequest } from '../api/model'
import { useSubmitBulk } from '../api/bulk'
import type { BulkTarget } from '../api/bulk'
import { problemBanner } from '../actions/problem'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'
import { useOpsDrawer } from '../ops/drawerState'
import type { EngineFailure } from '../search/partials'
import { FilterBulkModal } from './FilterBulkModal'
import { perEngineSplit, planFilterScope, planSelection } from './intersection'
import type { BulkVerbOffer } from './intersection'

interface Props {
  selected: ProcessInstanceRow[]
  /** Engines that failed this search — bulk over a partial set needs explicit ack. */
  failedEngines: EngineFailure[]
  /** True when scans were truncated (10k-DLQ hit etc.) — same acknowledgment gate. */
  truncated: boolean
  onSubmitted: () => void
  /** The current search criteria (URL state) — the filter-scope submit body. */
  criteria: SearchRequest | null
  /** Engine-reported match total across the result set — context ("~N"), not the scope. */
  matchTotal: number | undefined
  /** Rows the grid currently shows — "all visible selected" trips the affordance. */
  visibleCount: number
  engines: EngineDto[]
}

export function BulkBar({
  selected,
  failedEngines,
  truncated,
  onSubmitted,
  criteria,
  matchTotal,
  visibleCount,
  engines,
}: Props) {
  const [modalVerb, setModalVerb] = useState<BulkVerbOffer | null>(null)
  const [filterScope, setFilterScope] = useState(false)
  const [filterVerb, setFilterVerb] = useState<BulkVerbOffer | null>(null)
  const plan = useMemo(() => planSelection(selected), [selected])

  // A new search is a new scope — never carry filter mode across criteria.
  useEffect(() => {
    setFilterScope(false)
    setFilterVerb(null)
  }, [criteria])

  const filterable =
    criteria !== null && (criteria.statuses ?? []).length > 0 && (matchTotal ?? 0) > 0
  const allVisibleSelected = visibleCount > 0 && selected.length >= visibleCount
  const approx = matchTotal !== undefined ? `~${String(matchTotal)}` : 'all'

  if (filterScope && criteria !== null) {
    const offers = planFilterScope(criteria.statuses)
    return (
      <div className="bulk-bar bulk-bar-filter" role="toolbar" aria-label="bulk actions">
        <span className="bulk-count">
          Scope: <strong>{approx} instances matching the current filter</strong> — resolved
          server-side at execution, not this snapshot
        </span>
        <button
          type="button"
          className="copy-btn"
          onClick={() => {
            setFilterScope(false)
          }}
        >
          Back to checkbox selection
        </button>
        {offers.map((offer) => (
          <button
            key={offer.verb}
            type="button"
            className="copy-btn action-btn"
            disabled={!offer.enabled}
            title={offer.enabled ? offer.plain : offer.reason}
            onClick={() => {
              setFilterVerb(offer)
            }}
          >
            {offer.label}
          </button>
        ))}
        {filterVerb !== null && (
          <FilterBulkModal
            offer={filterVerb}
            criteria={criteria}
            matchTotal={matchTotal}
            engines={engines}
            onClose={() => {
              setFilterVerb(null)
            }}
            onSubmitted={() => {
              setFilterVerb(null)
              setFilterScope(false)
              onSubmitted()
            }}
          />
        )}
      </div>
    )
  }

  if (selected.length === 0) {
    // Standalone entry (v1.x #2): a filtered result set is bulk-actionable without
    // touching a single checkbox — the scope is the filter itself.
    if (!filterable) return null
    return (
      <div className="bulk-bar bulk-bar-slim" role="toolbar" aria-label="bulk actions">
        <button
          type="button"
          className="copy-btn"
          title="act on every instance matching the current filter — resolved server-side at execution"
          onClick={() => {
            setFilterScope(true)
          }}
        >
          Select all {approx} matching filter…
        </button>
      </div>
    )
  }

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
      {allVisibleSelected && filterable && (
        <button
          type="button"
          className="copy-btn"
          title="the whole page is selected — widen the scope to every instance matching the filter (server-resolved at execution)"
          onClick={() => {
            setFilterScope(true)
          }}
        >
          Select all {approx} matching filter…
        </button>
      )}
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
  const drawer = useOpsDrawer()
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
          // v1.x #1 handoff, shared with the triage group retry: open the drawer
          // focused on the fresh job instead of pointing at it with words.
          if (job.id !== undefined) drawer.focusJob(job.id)
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

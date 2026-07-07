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
import type { MeDto } from '../api/me'
import { roleOn } from '../api/me'
import { roleAtLeast } from '../actions/catalog'
import type { RoleHint } from '../actions/catalog'
import { problemBanner } from '../actions/problem'
import { ActionHint } from '../components/ActionHint'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'
import { useOpsDrawer } from '../ops/drawerState'
import type { EngineFailure } from '../search/partials'
import { enginesInScope, FilterBulkModal } from './FilterBulkModal'
import {
  perEngineSplit,
  planFilterScope,
  planSelection,
  reversibilityNote,
  targetEnvironment,
} from './intersection'
import type { BulkVerbOffer } from './intersection'

const ROLE_FLOOR: RoleHint = 'RESPONDER'

/** Usability round 1, Theme B: the FIRST target engine whose role is below the RESPONDER
 *  floor — greying ALL verbs uniformly, the same as ErrorGroupCard's per-verb gate. Unknown
 *  role (me not loaded / engine unmapped) stays optimistic, per roleOn's own contract. */
function belowFloorRole(me: MeDto | undefined, engineIds: Iterable<string>): RoleHint | null {
  for (const engineId of engineIds) {
    const role = roleOn(me, engineId)
    if (role !== null && !roleAtLeast(role, ROLE_FLOOR)) return role
  }
  return null
}

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
  /** The signed-in operator (SPEC §6 greyed-never-hidden) — threaded down from SearchPage
   *  the same way ErrorGroupCard obtains it, so the bar's role gate cannot drift from it. */
  me: MeDto | undefined
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
  me,
}: Props) {
  const [modalVerb, setModalVerb] = useState<BulkVerbOffer | null>(null)
  const [filterScope, setFilterScope] = useState(false)
  const [filterVerb, setFilterVerb] = useState<BulkVerbOffer | null>(null)
  const selectionRoleHint = useMemo(
    () => belowFloorRole(me, new Set(selected.map((row) => row.engineId ?? ''))),
    [me, selected],
  )
  const plan = useMemo(
    () => planSelection(selected, selectionRoleHint),
    [selected, selectionRoleHint],
  )

  // A new search is a new scope — never carry filter mode across criteria.
  useEffect(() => {
    setFilterScope(false)
    setFilterVerb(null)
  }, [criteria])

  const filterable =
    criteria !== null && (criteria.statuses ?? []).length > 0 && (matchTotal ?? 0) > 0
  const allVisibleSelected = visibleCount > 0 && selected.length >= visibleCount
  const approx = matchTotal !== undefined ? `~${String(matchTotal)}` : 'all'
  // Theme H1: never render "Select all all matching filter…" — the ~N only appears when known.
  const selectAllLabel =
    matchTotal !== undefined
      ? `Select all ${approx} matching filter…`
      : 'Select all matching filter…'
  const scopeCountHint =
    '~ = the count when this page loaded. The real list is re-checked at run time.'

  if (filterScope && criteria !== null) {
    const filterEngineIds = enginesInScope(criteria, engines).map((engine) => engine.id ?? '')
    const filterRoleHint = belowFloorRole(me, filterEngineIds)
    const offers = planFilterScope(criteria.statuses, filterRoleHint)
    return (
      <div className="bulk-bar bulk-bar-filter" role="toolbar" aria-label="bulk actions">
        <span className="bulk-count">
          Scope: <strong>{approx} instances matching the current filter</strong> — resolved
          server-side at execution, not this snapshot
        </span>
        <ActionHint tone="info" text={scopeCountHint} />
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
          <div className="action-slot" key={offer.verb}>
            <button
              type="button"
              className="copy-btn action-btn"
              disabled={!offer.enabled}
              aria-describedby={offer.enabled ? undefined : `filter-${offer.verb}-hint`}
              title={offer.enabled ? offer.plain : (offer.detail ?? offer.reason)}
              onClick={() => {
                setFilterVerb(offer)
              }}
            >
              {offer.label}
            </button>
            {!offer.enabled && offer.reason !== undefined && (
              <ActionHint id={`filter-${offer.verb}-hint`} text={offer.reason} tone="gate" />
            )}
          </div>
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
        <div className="action-slot">
          <button
            type="button"
            className="copy-btn"
            title="act on every instance matching the current filter — resolved server-side at execution"
            onClick={() => {
              setFilterScope(true)
            }}
          >
            {selectAllLabel}
          </button>
          <ActionHint tone="info" text={scopeCountHint} />
        </div>
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
        <div className="action-slot">
          <button
            type="button"
            className="copy-btn"
            title="the whole page is selected — widen the scope to every instance matching the filter (server-resolved at execution)"
            onClick={() => {
              setFilterScope(true)
            }}
          >
            {selectAllLabel}
          </button>
          <ActionHint tone="info" text={scopeCountHint} />
        </div>
      )}
      {plan.offers.map((offer) => (
        <div className="action-slot" key={offer.verb}>
          <button
            type="button"
            className="copy-btn action-btn"
            disabled={!offer.enabled}
            aria-describedby={offer.enabled ? undefined : `bulk-${offer.verb}-hint`}
            title={offer.enabled ? offer.plain : (offer.detail ?? offer.reason)}
            onClick={() => {
              setModalVerb(offer)
            }}
          >
            {offer.label}
          </button>
          {!offer.enabled && offer.reason !== undefined && (
            <ActionHint id={`bulk-${offer.verb}-hint`} text={offer.reason} tone="gate" />
          )}
        </div>
      ))}
      {modalVerb !== null && (
        <BulkSubmitModal
          offer={modalVerb}
          targets={plan.targets}
          engines={engines}
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
  engines,
  failedEngines,
  truncated,
  onClose,
  onSubmitted,
}: {
  offer: BulkVerbOffer
  targets: ProcessInstanceRow[]
  engines: EngineDto[]
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
  const environment = useMemo(() => targetEnvironment(targets, engines), [targets, engines])
  // SPEC §7: bulk over a partial result set is BLOCKED until explicitly acknowledged.
  const partial = failedEngines.length > 0 || truncated
  // C-front: unified with the sibling modals — always required, never the optional escape.
  const reasonOk = reason.trim().length >= 10
  const canSubmit = !submit.isPending && reasonOk && (!partial || acknowledged)
  const partialBlocked = partial && !acknowledged
  const shortReason = partialBlocked
    ? 'Blocked: acknowledge the partial result set first'
    : !reasonOk
      ? 'Reason too short — 10+ characters'
      : undefined
  const longDetail = partialBlocked
    ? 'acknowledge the partial result set first'
    : !reasonOk
      ? 'a reason of at least 10 characters is required'
      : undefined

  const dispatch = () => {
    const items: BulkTarget[] = targets.map((row) => ({
      engineId: row.engineId ?? '',
      instanceId: row.processInstanceId ?? '',
    }))
    submit.mutate(
      { verb: offer.verb, reason: reason.trim(), items },
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
      environment={environment}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <div className="action-slot">
            <button
              type="button"
              className="primary"
              disabled={!canSubmit}
              aria-describedby={longDetail !== undefined ? 'bulk-submit-hint' : undefined}
              title={longDetail}
              onClick={dispatch}
            >
              {submit.isPending
                ? 'Submitting…'
                : `${offer.label} — ${String(targets.length)} instance${targets.length === 1 ? '' : 's'}`}
            </button>
            {shortReason !== undefined && (
              <ActionHint id="bulk-submit-hint" text={shortReason} tone="gate" />
            )}
          </div>
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
      <p className="strip-note">{reversibilityNote(offer.verb)}</p>

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
        Why are you doing this? (required, 10+ characters — saved to the audit trail on every item)
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

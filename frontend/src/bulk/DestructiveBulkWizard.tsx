// The tier-4 destructive-bulk wizard (SPEC §6/§7, issue #100): "terminate every instance
// matching this filter" at ADMIN scale. This PR ships terminate-delete only; the guard ladder's
// heaviest tier — scope enumeration re-resolved server-fresh at submit (never trusting THIS
// preview), a mandatory narrowing filter (refuse-unscoped, enforced server-side), and on any
// PROD-touching scope a typed COUNT attestation checked against that fresh resolution. A count
// drift (the scope moved between preview and submit) is shown, not silently swallowed.
import { useEffect } from 'react'
import type { EngineDto, SearchRequest } from '../api/model'
import { usePreviewDestructiveBulk, useSubmitDestructiveBulk } from '../api/bulk'
import { reasonRule } from '../actions/catalog'
import { useProdGuard } from '../actions/guard'
import { isReauthChallenge, problemBanner } from '../actions/problem'
import { ReauthNotice, useReauthStale } from '../actions/ReauthNotice'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { TicketField, ticketValue } from '../components/TicketField'
import { useToast } from '../components/toast'
import { useOpsDrawer } from '../ops/drawerState'
import { criteriaChips } from './FilterBulkModal'

interface Props {
  criteria: SearchRequest
  engines: EngineDto[]
  onClose: () => void
  onSubmitted: () => void
}

const VERB = 'terminate-delete'

export function DestructiveBulkWizard({ criteria, engines, onClose, onSubmitted }: Props) {
  const toast = useToast()
  const drawer = useOpsDrawer()
  const preview = usePreviewDestructiveBulk()
  const submit = useSubmitDestructiveBulk()

  // Auto-preview on open — the operator's first sight of this wizard is already the resolved
  // scope, not a blank form (there is no separate criteria-builder here; the wizard inherits
  // whatever filter the operator already narrowed the results grid to).
  useEffect(() => {
    preview.mutate({
      criteria,
      verb: VERB,
      reason: '',
      ticketId: undefined,
      confirmedCount: undefined,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const data = preview.data
  const prod = data?.prodInScope === true
  const environment = prod ? 'prod' : 'dev'
  const expectedToken = data !== undefined ? String(data.count) : undefined

  const guard = useProdGuard({
    reasonRule: reasonRule(3, environment),
    environment,
    expectedToken,
    needsToken: prod, // the wizard's OWN gate, not the single-target business-key one
  })
  const { reasonOk, tokenOk } = guard

  const problem = submit.error?.problem
  const driftProblem = problem?.code === 'bulk-count-drift' ? problem : undefined
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
  const reauthNeeded = useReauthStale() || (problem !== undefined && isReauthChallenge(problem))

  const rePreview = () => {
    guard.setTyped('')
    preview.mutate({
      criteria,
      verb: VERB,
      reason: '',
      ticketId: undefined,
      confirmedCount: undefined,
    })
  }

  const confirm = () => {
    if (data === undefined) return
    submit.mutate(
      {
        criteria,
        verb: VERB,
        reason: guard.reason.trim(),
        ticketId: ticketValue(guard.ticket),
        confirmedCount: prod ? Number(guard.typed) : undefined,
      },
      {
        onSuccess: (job) => {
          toast({
            kind: 'success',
            text: `Destructive bulk dispatched — ${String(job.totalItems ?? 0)} instance${
              job.totalItems === 1 ? '' : 's'
            } resolved server-side. Progress in the operations drawer.`,
          })
          if (job.id !== undefined) drawer.focusJob(job.id)
          onSubmitted()
        },
      },
    )
  }

  const resolvedCount = data?.count ?? 0
  const canSubmit = data !== undefined && resolvedCount > 0 && reasonOk && tokenOk

  const shortReason = reauthNeeded
    ? 'Blocked: re-authenticate to enable — sign-in too old'
    : data === undefined
      ? 'Resolving the scope…'
      : resolvedCount === 0
        ? 'Nothing matches — nothing to terminate'
        : !reasonOk
          ? 'Reason too short — 10+ characters'
          : !tokenOk
            ? `Type ${expectedToken ?? ''} to enable`
            : dispatchedMaybe
              ? 'Blocked: previous attempt outcome unknown — do not resubmit'
              : undefined

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <button
        type="button"
        className="danger"
        disabled={!canSubmit || submit.isPending || dispatchedMaybe || reauthNeeded}
        aria-describedby={shortReason !== undefined ? 'destructive-bulk-submit-hint' : undefined}
        title={shortReason}
        onClick={confirm}
      >
        {submit.isPending ? 'Dispatching…' : 'Terminate — all matching the filter'}
      </button>
    </>
  )

  return (
    <ModalShell
      title="Destructive bulk — terminate every instance matching the current filter"
      environment={environment}
      onClose={onClose}
      footer={footer}
    >
      <p className="modal-verb-badges">
        <span className="reversibility rev-irreversible">IRREVERSIBLE</span> instance state and
        history are deleted — there is no compensating verb.
      </p>

      <div className="modal-target">
        <p className="modal-target-heading">
          Scope: the filter{prod && ' — including PRODUCTION'}:
        </p>
        <p>
          {criteriaChips(criteria).map((chip) => (
            <code key={chip} className="criteria-chip">
              {chip}
            </code>
          ))}
        </p>
        <p className="strip-note">
          Capped at 200 instances per destructive-bulk job (server-enforced) — narrow the filter and
          run in slices for a larger set. Refused outright if the filter carries no narrowing
          dimension beyond status (SPEC §6 tier 4).
        </p>
      </div>

      {preview.isPending && <p className="strip-note">Resolving the scope…</p>}
      {preview.isError && (
        <div className="error-banner" role="alert">
          {problemBanner(preview.error.problem)}
        </div>
      )}

      {data !== undefined && (
        <div className="modal-target">
          <p className="modal-target-heading">
            Resolves to <strong>{data.count}</strong> instance{data.count === 1 ? '' : 's'}
            {data.capped && ' (showing the first 50 — the count above is exact)'}:
          </p>
          <ul className="bulk-per-engine-split">
            {Object.entries(data.perEngineCounts ?? {}).map(([engineId, count]) => {
              const engine = engines.find((e) => e.id === engineId)
              return (
                <li key={engineId}>
                  {engine?.name ?? engineId}: {count}
                </li>
              )
            })}
          </ul>
        </div>
      )}

      {driftProblem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(driftProblem)}{' '}
          <button type="button" onClick={rePreview}>
            Refresh the scope
          </button>
        </div>
      )}

      {data !== undefined && resolvedCount > 0 && (
        <>
          <GuardFields
            guard={guard}
            reasonLabel="Why are you terminating these instances? (required, 10+ characters — saved to the audit trail on every item)"
            expectedToken={expectedToken}
            tokenFieldLabel={
              <>
                Type <code>{expectedToken}</code> — the resolved instance count — to confirm acting
                on production
              </>
            }
          />
          <TicketField value={guard.ticket} onChange={guard.setTicket} />
        </>
      )}

      {reauthNeeded ? (
        <ReauthNotice />
      ) : (
        driftProblem === undefined &&
        problem !== undefined && (
          <div className="error-banner" role="alert">
            {problemBanner(problem)}
          </div>
        )
      )}
    </ModalShell>
  )
}

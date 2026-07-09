// The confirmation for select-all-matching-filter bulk (v1.x #2). Deliberately NOT a
// "type the count" gate: the members are re-resolved SERVER-SIDE at execution time, so a
// typed count would attest a number that may already be stale — on PROD the stable typed
// token is the definition key (or the engine id when the filter names none), the same
// doctrine as the error-class group retry. The modal restates the CRITERIA (the binding
// scope) and shows the grid's count as context; the binding count is the one the job
// reports after resolution.
import { useMemo, useState } from 'react'
import type { EngineDto, SearchRequest } from '../api/model'
import { useSubmitBulkFilter } from '../api/bulk'
import { reasonRule, reasonValid } from '../actions/catalog'
import { isReauthChallenge, problemBanner } from '../actions/problem'
import { ReauthNotice, useReauthStale } from '../actions/ReauthNotice'
import { ActionHint } from '../components/ActionHint'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'
import { useOpsDrawer } from '../ops/drawerState'
import { reversibilityNote } from './intersection'
import type { BulkVerbOffer } from './intersection'

interface Props {
  offer: BulkVerbOffer
  criteria: SearchRequest
  /** The grid snapshot's match total — context only ("~N"), never the binding scope. */
  matchTotal: number | undefined
  engines: EngineDto[]
  onClose: () => void
  onSubmitted: () => void
}

/** Engines the criteria actually target: the named ones, or every enabled engine. */
export function enginesInScope(criteria: SearchRequest, engines: EngineDto[]): EngineDto[] {
  const ids = criteria.engineIds ?? []
  if (ids.length === 0) return engines
  return engines.filter((engine) => engine.id !== undefined && ids.includes(engine.id))
}

/** The PROD typed token: stable identity, never a raceable count. */
export function prodConfirmToken(criteria: SearchRequest, prodEngines: EngineDto[]): string {
  if (criteria.processDefinitionKey !== undefined && criteria.processDefinitionKey !== '') {
    return criteria.processDefinitionKey
  }
  if (prodEngines.length === 1 && prodEngines[0].id !== undefined) return prodEngines[0].id
  return 'ALL'
}

/** Compact restatement of the criteria — the scope the operator is attesting to. */
export function criteriaChips(criteria: SearchRequest): string[] {
  const chips: string[] = []
  if ((criteria.statuses ?? []).length > 0) chips.push((criteria.statuses ?? []).join(' + '))
  if (criteria.processDefinitionKey !== undefined && criteria.processDefinitionKey !== '') {
    chips.push(
      criteria.definitionVersion !== undefined
        ? `${criteria.processDefinitionKey} v${String(criteria.definitionVersion)}`
        : criteria.processDefinitionKey,
    )
  }
  if ((criteria.engineIds ?? []).length > 0)
    chips.push(`engines: ${(criteria.engineIds ?? []).join(', ')}`)
  if (criteria.businessKey !== undefined && criteria.businessKey !== '')
    chips.push(`business key = ${criteria.businessKey}`)
  if (criteria.businessKeyLike !== undefined && criteria.businessKeyLike !== '')
    chips.push(`business key ~ ${criteria.businessKeyLike}`)
  if (criteria.errorText !== undefined && criteria.errorText !== '')
    chips.push(`error contains "${criteria.errorText}"`)
  if (criteria.signatureHash !== undefined && criteria.signatureHash !== '')
    chips.push('one error class')
  if (criteria.currentActivity !== undefined && criteria.currentActivity !== '')
    chips.push(`at activity ~ ${criteria.currentActivity}`)
  if (criteria.startedAfter !== undefined || criteria.startedBefore !== undefined)
    chips.push('started-time window')
  if (criteria.failureTimeAfter !== undefined || criteria.failureTimeBefore !== undefined)
    chips.push('failure-time window')
  if ((criteria.variables ?? []).length > 0)
    chips.push(`${String((criteria.variables ?? []).length)} variable filter(s)`)
  return chips
}

export function FilterBulkModal({
  offer,
  criteria,
  matchTotal,
  engines,
  onClose,
  onSubmitted,
}: Props) {
  const toast = useToast()
  const drawer = useOpsDrawer()
  const submit = useSubmitBulkFilter()
  const [reason, setReason] = useState('')
  const [typed, setTyped] = useState('')

  const scope = useMemo(() => enginesInScope(criteria, engines), [criteria, engines])
  const prodEngines = scope.filter((engine) => engine.environment?.toLowerCase() === 'prod')
  const prod = prodEngines.length > 0
  const token = prodConfirmToken(criteria, prodEngines)
  const environment = prod ? 'prod' : (scope[0]?.environment ?? undefined)

  // The reason is ALWAYS mandatory here (tier-3 spirit): the operator never enumerated
  // these instances, so the audit trail carries the why.
  const rule = reasonRule(3, environment)
  const reasonOk = reasonValid(reason, rule) && reason.trim() !== ''
  const tokenOk = !prod || typed === token
  // UNKNOWN outcome ⇒ the job may exist server-side — never resubmit from this modal
  // (corrective-actions §4). Refusals (4xx) leave the button usable after an edit.
  const problem = submit.error?.problem
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
  // Dangerous-set freshness (IDP-SECURITY.md §5): bulk submit is challenged regardless of verb
  // tier — pre-empt at open via the /api/me hint, or react to the 401 reauth-required answer.
  const reauthNeeded = useReauthStale() || (problem !== undefined && isReauthChallenge(problem))

  const confirm = () => {
    submit.mutate(
      { criteria, verb: offer.verb, reason: reason.trim() },
      {
        onSuccess: (job) => {
          toast({
            kind: 'success',
            text: `Filter bulk dispatched — ${String(job.totalItems ?? 0)} instance${
              job.totalItems === 1 ? '' : 's'
            } resolved server-side. Progress in the operations drawer.`,
          })
          if (job.id !== undefined) drawer.focusJob(job.id)
          onSubmitted()
        },
      },
    )
  }

  const shortReason = reauthNeeded
    ? 'Blocked: re-authenticate to enable — sign-in too old'
    : !reasonOk
      ? 'Reason too short — 10+ characters'
      : !tokenOk
        ? `Type ${token} to enable`
        : dispatchedMaybe
          ? 'Blocked: previous attempt outcome unknown — do not resubmit'
          : undefined
  const longDetail = !reasonOk
    ? 'a reason of at least 10 characters is required'
    : !tokenOk
      ? `type ${token} exactly to enable`
      : undefined

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <div className="action-slot">
        <button
          type="button"
          className="danger"
          disabled={!reasonOk || !tokenOk || submit.isPending || dispatchedMaybe || reauthNeeded}
          aria-describedby={shortReason !== undefined ? 'filter-bulk-submit-hint' : undefined}
          title={longDetail}
          onClick={confirm}
        >
          {submit.isPending ? 'Dispatching…' : `${offer.label} — all matching the filter`}
        </button>
        {shortReason !== undefined && (
          <ActionHint id="filter-bulk-submit-hint" text={shortReason} tone="gate" />
        )}
      </div>
    </>
  )

  return (
    <ModalShell
      title={`${offer.label} — every instance matching the current filter`}
      environment={environment}
      onClose={onClose}
      footer={footer}
    >
      <div className="modal-target">
        <p className="modal-target-heading">
          Scope: <strong>the filter</strong>
          {prod && ' — including PRODUCTION engine' + (prodEngines.length === 1 ? '' : 's')}:
        </p>
        <p>
          {criteriaChips(criteria).map((chip) => (
            <code key={chip} className="criteria-chip">
              {chip}
            </code>
          ))}
        </p>
        <p className="strip-note">
          The grid shows {matchTotal !== undefined ? `~${String(matchTotal)}` : 'a page of'}{' '}
          matching instances, but this action applies to the{' '}
          <strong>server-resolved filter at execution time</strong> — not this snapshot. Instances
          that drained since the grid rendered are left alone; new matches are included. The job
          reports the exact resolved list, recorded in the audit trail before anything runs.
        </p>
      </div>

      <p className="strip-note">{reversibilityNote(offer.verb)}</p>

      <label className="modal-field">
        Why are you doing this? (required, 10+ characters — saved to the audit trail on every item)
        <textarea
          value={reason}
          rows={2}
          maxLength={2000}
          onChange={(event) => {
            setReason(event.target.value)
          }}
        />
      </label>

      {prod && (
        <label className="modal-field">
          Type <code>{token}</code> to confirm acting on production
          <input
            type="text"
            value={typed}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setTyped(event.target.value)
            }}
          />
        </label>
      )}

      {reauthNeeded ? (
        <ReauthNotice />
      ) : (
        problem !== undefined && (
          <div className="error-banner" role="alert">
            {problemBanner(problem)}
          </div>
        )
      )}
    </ModalShell>
  )
}

// The Tier-3 confirmation for the error-class group retry (v1.x #1). Deliberately NOT the
// tier-4 "type the count" wizard: the member list is re-resolved SERVER-SIDE at dispatch,
// so a typed count would attest a number that may already be stale — the stable typed
// token on PROD is the definition key instead (corrective-actions §3 still holds: bulk on
// prod never dispatches on a bare confirm). The modal restates the signature, the scope
// and the card's count as a lower-bound-honest context line; the BINDING count is the
// resolved one reported back on the job itself.
import { useState } from 'react'
import type { EngineDto, ErrorGroup } from '../api/model'
import { useSubmitBulkErrorClass } from '../api/bulk'
import { useQueryClient } from '@tanstack/react-query'
import { reasonRule, reasonValid } from '../actions/catalog'
import { problemBanner } from '../actions/problem'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'
import { useOpsDrawer } from '../ops/drawerState'

interface Props {
  group: ErrorGroup
  engineId: string
  engine: EngineDto | undefined
  definitionKey: string
  /** Numeric deployed version (the card chip's vN). */
  version: number
  /** The card's defKey:vN count — failure-lane total, shown as context (≥ when truncated). */
  count: number
  lowerBound: boolean
  onClose: () => void
}

export function RetryGroupModal({
  group,
  engineId,
  engine,
  definitionKey,
  version,
  count,
  lowerBound,
  onClose,
}: Props) {
  const toast = useToast()
  const queryClient = useQueryClient()
  const drawer = useOpsDrawer()
  const submit = useSubmitBulkErrorClass()
  const [reason, setReason] = useState('')
  const [typed, setTyped] = useState('')

  const environment = engine?.environment
  const prod = environment?.toLowerCase() === 'prod'
  const rule = reasonRule(3, environment)
  const reasonOk = reasonValid(reason, rule) && reason.trim() !== ''
  const tokenOk = !prod || typed === definitionKey
  const coordinatesOk = group.signatureHash !== undefined && group.algoVersion !== undefined
  // UNKNOWN outcome ⇒ the job may exist server-side — never resubmit from this modal
  // (corrective-actions §4). Refusals (4xx) leave the button usable after an edit.
  const problem = submit.error?.problem
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
  const prefix = lowerBound ? '≥ ' : ''

  const confirm = () => {
    submit.mutate(
      {
        signatureHash: group.signatureHash,
        algoVersion: group.algoVersion,
        processDefinitionKey: definitionKey,
        definitionVersion: version,
        engineId,
        reason: reason.trim(),
      },
      {
        onSuccess: (job) => {
          toast({
            kind: 'success',
            text: `Group retry dispatched — ${String(job.totalItems ?? 0)} dead-lettered instance${
              job.totalItems === 1 ? '' : 's'
            } resolved server-side. Progress in the operations drawer.`,
          })
          // The handoff: drawer open, focused on the fresh job. Counts refresh again
          // when the job settles (OpsDrawer watches the transition) — no optimistic state.
          if (job.id !== undefined) drawer.focusJob(job.id)
          void queryClient.invalidateQueries({ queryKey: ['triage'] })
          onClose()
        },
      },
    )
  }

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <button
        type="button"
        className="danger"
        disabled={!reasonOk || !tokenOk || !coordinatesOk || submit.isPending || dispatchedMaybe}
        title={
          !coordinatesOk
            ? 'this group is missing its signature coordinates — refresh the landing'
            : !reasonOk
              ? 'a reason of at least 10 characters is required'
              : !tokenOk
                ? 'type the definition key exactly to enable'
                : undefined
        }
        onClick={confirm}
      >
        {submit.isPending ? 'Dispatching…' : `Retry group — ${definitionKey} v${String(version)}`}
      </button>
    </>
  )

  return (
    <ModalShell
      title="Retry group — run every failed step in this error class again"
      environment={environment}
      onClose={onClose}
      footer={footer}
    >
      <div className="modal-target">
        <p className="modal-target-heading">
          On <strong>{engine?.name ?? engineId}</strong>
          {prod && ' — a PRODUCTION engine'}:
        </p>
        <p>
          <code>{definitionKey}</code> v{version} · error class{' '}
          <code>{group.exceptionClass ?? '(unknown exception)'}</code>
        </p>
        <p className="normalized-message">{group.normalizedMessage ?? '(no message)'}</p>
        <p className="strip-note">
          The card shows {prefix}
          {count} failing instance{count === 1 ? '' : 's'} in this scope. The retry targets the ones{' '}
          <strong>currently dead-lettered</strong> — the member list is resolved server-side at
          dispatch (instances that drained or still hold retries are left alone), and the job
          reports the exact resolved count.
        </p>
      </div>

      <label className="modal-field">
        Reason (required, at least 10 characters — lands in the audit trail)
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
          Type the definition key <code>{definitionKey}</code> to enable the confirm button
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

      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}

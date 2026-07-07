// Task reassign / return-to-team (v1.x #6). Both are tier-1 OPERATOR verbs on ACTIVE user
// tasks. Reassign takes a target user id; return-to-team clears the assignee so the task
// falls back to its candidate groups. Reason discipline follows the same §6 ladder as every
// mutation (required on prod, ≥10 chars when present); every modal carries the SERVER-computed
// "Show as cURL". Cancel-focused, Enter-never-submits (ModalShell). No resubmit after an
// UNKNOWN outcome (corrective-actions §4).
import { useState } from 'react'
import type { TaskDto } from '../api/model'
import type { ActionRequest } from '../api/actions'
import { ModalShell } from '../components/ModalShell'
import { CurlPreview } from '../components/CurlPreview'
import { VERBS, reasonRule } from './catalog'
import { buildTaskAssignBody } from './taskAssign'
import type { TaskAssignMode } from './taskAssign'
import { problemBanner } from './problem'
import type { ActionProblem } from './problem'

export type { TaskAssignMode } from './taskAssign'

interface Props {
  mode: TaskAssignMode
  engineId: string
  instanceId: string
  engineName: string
  environment?: string
  task: TaskDto
  pending: boolean
  problem?: ActionProblem
  onConfirm: (verb: string, body: ActionRequest) => void
  onClose: () => void
}

export function TaskAssignModal({
  mode,
  engineId,
  instanceId,
  engineName,
  environment,
  task,
  pending,
  problem,
  onConfirm,
  onClose,
}: Props) {
  const meta = mode === 'reassign' ? VERBS.reassignTask : VERBS.unassignTask
  const [assignee, setAssignee] = useState('')
  const [reason, setReason] = useState('')

  const prod = environment?.toLowerCase() === 'prod'
  const rule = reasonRule(meta.tier, environment)
  const trimmedReason = reason.trim()
  const reasonOk = trimmedReason === '' ? !rule.required : trimmedReason.length >= rule.minLength
  const trimmedAssignee = assignee.trim()
  const assigneeOk = mode === 'return' || trimmedAssignee !== ''
  // UNKNOWN outcome ⇒ the pre-flight/dispatch may have applied — never resubmit (§4).
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'

  const taskId = task.id ?? ''
  const body: ActionRequest = buildTaskAssignBody(mode, taskId, assignee, reason)

  const confirmLabel =
    mode === 'reassign'
      ? trimmedAssignee === ''
        ? 'Reassign task'
        : `Reassign to ${trimmedAssignee}`
      : `Return ${task.name ?? 'the task'} to its team`

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <button
        type="button"
        disabled={!assigneeOk || !reasonOk || pending || dispatchedMaybe}
        title={
          !assigneeOk
            ? 'enter the target user id to enable'
            : !reasonOk
              ? 'a reason of at least 10 characters is required'
              : undefined
        }
        onClick={() => {
          onConfirm(meta.verb, body)
        }}
      >
        {pending ? 'Dispatching…' : confirmLabel}
      </button>
    </>
  )

  return (
    <ModalShell
      title={`${meta.label} — ${meta.plain}`}
      environment={environment}
      onClose={onClose}
      footer={footer}
    >
      <p className="modal-verb-badges">
        <span className={`reversibility rev-${meta.reversibility.toLowerCase()}`}>
          {meta.reversibility}
        </span>{' '}
        {meta.reversibilityNote}
      </p>
      <div className="modal-target">
        <p className="modal-target-heading">
          On <strong>{engineName}</strong>
          {prod && ' — a PRODUCTION engine'}:
        </p>
        <p>
          Task <strong>{task.name ?? '(unnamed)'}</strong> <code>{taskId}</code>
          {task.taskDefinitionKey !== undefined && (
            <>
              {' '}
              (<code>{task.taskDefinitionKey}</code>)
            </>
          )}
        </p>
        <p>
          Currently assigned to{' '}
          {task.assignee !== undefined ? (
            <strong>{task.assignee}</strong>
          ) : (
            <span className="value-muted">no one (candidate groups only)</span>
          )}
        </p>
        {mode === 'return' && (
          <p className="strip-note">
            Clearing the assignee returns the task to its candidate groups — anyone in the team can
            claim it next.
          </p>
        )}
      </div>

      {mode === 'reassign' && (
        <label className="modal-field">
          Target user id (the new assignee)
          <input
            type="text"
            value={assignee}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setAssignee(event.target.value)
            }}
          />
        </label>
      )}

      <label className="modal-field">
        Reason{rule.required ? ' (required, at least 10 characters' : ' (optional'} — lands in the
        audit trail)
        <textarea
          value={reason}
          rows={2}
          maxLength={2000}
          onChange={(event) => {
            setReason(event.target.value)
          }}
        />
      </label>

      <CurlPreview engineId={engineId} instanceId={instanceId} verb={meta.verb} body={body} />

      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}

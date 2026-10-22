import { useState } from 'react'
import { fetchActionCurl } from '../api/actions'
import type { ActionRequest } from '../api/actions'
import type { TaskDto } from '../api/model'
import { ModalShell } from '../components/ModalShell'
import { CurlPreview } from '../components/CurlPreview'
import { GuardFields } from '../components/GuardFields'
import { VERBS, reasonRule } from './catalog'
import { useProdGuard } from './guard'
import { problemBanner } from './problem'
import type { ActionProblem } from './problem'

export type TaskVerbMode =
  'claim' | 'delegate' | 'resolve' | 'due' | 'add-link' | 'remove-link' | 'delete'

const META = {
  claim: VERBS.claimTask,
  delegate: VERBS.delegateTask,
  resolve: VERBS.resolveTask,
  due: VERBS.updateTask,
  'add-link': VERBS.addIdentityLink,
  'remove-link': VERBS.removeIdentityLink,
  delete: VERBS.deleteTask,
} as const

interface Props {
  mode: TaskVerbMode
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

export function TaskVerbModal({
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
  const meta = META[mode]
  const taskId = task.id ?? ''
  const [assignee, setAssignee] = useState('')
  const [due, setDue] = useState('')
  const [family, setFamily] = useState<'users' | 'groups'>('groups')
  const [identityId, setIdentityId] = useState('')
  const rule = reasonRule(meta.tier, environment)
  const guard = useProdGuard({
    reasonRule: rule,
    environment,
    expectedToken: mode === 'delete' ? taskId : undefined,
  })
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
  const trimmedAssignee = assignee.trim()
  const trimmedIdentity = identityId.trim()
  const dueIso = due === '' ? '' : new Date(due).toISOString()
  const extraOk =
    mode === 'claim' ||
    mode === 'resolve' ||
    (mode === 'delegate' && trimmedAssignee !== '') ||
    (mode === 'due' && dueIso !== '' && !Number.isNaN(Date.parse(dueIso))) ||
    ((mode === 'add-link' || mode === 'remove-link') && trimmedIdentity !== '') ||
    mode === 'delete'

  const body: ActionRequest = {
    taskId,
    ...(guard.reason.trim() === '' ? {} : { reason: guard.reason.trim() }),
    ...(mode === 'delete' && guard.typed !== '' ? { confirmToken: guard.typed } : {}),
    ...(mode === 'claim' && trimmedAssignee !== '' ? { assignee: trimmedAssignee } : {}),
    ...(mode === 'delegate' ? { assignee: trimmedAssignee } : {}),
    ...(mode === 'due' ? { task: { dueDate: dueIso } } : {}),
    ...(mode === 'add-link' || mode === 'remove-link'
      ? { link: { family, identityId: trimmedIdentity, type: 'candidate' } }
      : {}),
  }

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <button
        type="button"
        disabled={!extraOk || !guard.reasonOk || !guard.tokenOk || pending || dispatchedMaybe}
        onClick={() => {
          onConfirm(meta.verb, body)
        }}
      >
        {pending ? 'Dispatching…' : meta.label}
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
        <p>
          On <strong>{engineName}</strong>: task <strong>{task.name ?? '(unnamed)'}</strong>{' '}
          <code>{taskId}</code>
        </p>
        {mode === 'due' && (
          <p>
            Due date {task.dueDate ?? '(none)'} → {dueIso || '(pick a time)'}
          </p>
        )}
        {mode === 'claim' && <p>Leave the user empty to unclaim.</p>}
        {mode === 'resolve' && <p>Resolve only works after a delegate.</p>}
      </div>
      {(mode === 'claim' || mode === 'delegate') && (
        <label className="modal-field">
          User id
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
      {mode === 'due' && (
        <label className="modal-field">
          New due date
          <input
            type="datetime-local"
            value={due}
            onChange={(event) => {
              setDue(event.target.value)
            }}
          />
        </label>
      )}
      {(mode === 'add-link' || mode === 'remove-link') && (
        <>
          <label className="modal-field">
            Family
            <select
              value={family}
              onChange={(event) => {
                setFamily(event.target.value === 'users' ? 'users' : 'groups')
              }}
            >
              <option value="groups">groups</option>
              <option value="users">users</option>
            </select>
          </label>
          <label className="modal-field">
            Identity id
            <input
              type="text"
              value={identityId}
              autoComplete="off"
              spellCheck={false}
              onChange={(event) => {
                setIdentityId(event.target.value)
              }}
            />
          </label>
        </>
      )}
      <GuardFields guard={guard} expectedToken={mode === 'delete' ? taskId : undefined} />
      <CurlPreview
        queryKey={['instance', engineId, instanceId, meta.verb, JSON.stringify(body)]}
        fetchCurl={() => fetchActionCurl(engineId, instanceId, meta.verb, body)}
      />
      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}

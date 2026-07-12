import { RawJsonExport } from '../RawJsonExport'
import { useState } from 'react'
import type { TaskDto } from '../../api/model'
import { formatSeconds } from '../../lib/format'
import { Ts } from '../../lib/Ts'
import { useInstanceTasks } from '../useInstanceQueries'
import { useInstanceAction } from '../../api/actions'
import type { ActionRequest } from '../../api/actions'
import { useEngines } from '../../api/useEngines'
import { roleOn, useMe } from '../../api/me'
import { actionGate, VERBS } from '../../actions/catalog'
import { TaskAssignModal } from '../../actions/TaskAssignModal'
import type { TaskAssignMode } from '../../actions/TaskAssignModal'
import type { ActionProblem } from '../../actions/problem'
import { ActionHint } from '../../components/ActionHint'
import { useToast } from '../../components/toast'

interface Props {
  engineId: string
  instanceId: string
}

interface ModalState {
  mode: TaskAssignMode
  task: TaskDto
}

/**
 * User tasks, completed AND open, one ledger (SPEC §4) — bound to the historic ∪ runtime
 * union of GET …/tasks. Reassign / return-to-team (v1.x #6) ride the row menu of ACTIVE
 * tasks only; both are tier-1 OPERATOR verbs through the shared action dispatcher.
 */
export default function TasksTab({ engineId, instanceId }: Props) {
  const query = useInstanceTasks(engineId, instanceId)
  const engines = useEngines()
  const engine = (engines.data ?? []).find((candidate) => candidate.id === engineId)
  const me = useMe()
  const roleHint = roleOn(me.data, engineId)
  const action = useInstanceAction(engineId, instanceId)
  const toast = useToast()

  const [modal, setModal] = useState<ModalState | null>(null)
  const [problem, setProblem] = useState<ActionProblem | undefined>(undefined)

  // Role/engine-mode gate (greyed-never-hidden); the BFF stays the real gate. Reassign and
  // return-to-team share the same tier-1 OPERATOR floor, so one gate covers both.
  const gate = actionGate({
    meta: VERBS.reassignTask,
    roleHint,
    engineMode: engine?.mode,
    environment: engine?.environment,
  })

  const openModal = (mode: TaskAssignMode, task: TaskDto) => {
    setProblem(undefined)
    setModal({ mode, task })
  }

  const dispatch = (verb: string, body: ActionRequest) => {
    action.mutate(
      { verb, body },
      {
        onSuccess: (result) => {
          setModal(null)
          setProblem(undefined)
          toast({ kind: 'success', text: result.deltaStatement ?? 'Done.' })
        },
        onError: (error) => {
          setProblem(error.problem)
        },
      },
    )
  }

  if (query.isPending) return <div className="zero-state">Loading user tasks…</div>
  if (query.isError) {
    return (
      <div className="error-banner" role="alert">
        Tasks unavailable: {query.error.message}
      </div>
    )
  }
  const { tasks = [], total = 0, truncated = false } = query.data
  if (tasks.length === 0) {
    return (
      <div className="zero-state">
        No user tasks — this instance runs on service tasks and events alone.
      </div>
    )
  }
  return (
    <div className="tasks-tab">
      <RawJsonExport data={query.data} filename={`${engineId}-${instanceId}-tasks.json`} />
      {truncated && (
        <p className="strip-note">
          Showing {tasks.length} of {total} tasks — the engine page cap truncated this list.
        </p>
      )}
      <table className="ledger-table">
        <thead>
          <tr>
            <th scope="col">Task</th>
            <th scope="col">State</th>
            <th scope="col">Assignee</th>
            <th scope="col">Created</th>
            <th scope="col">Due</th>
            <th scope="col">Completed</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((task, index) => (
            <TaskRow
              key={task.id ?? `row-${String(index)}`}
              task={task}
              gate={gate}
              onReassign={() => {
                openModal('reassign', task)
              }}
              onReturn={() => {
                openModal('return', task)
              }}
            />
          ))}
        </tbody>
      </table>

      {modal !== null && (
        <TaskAssignModal
          mode={modal.mode}
          engineId={engineId}
          instanceId={instanceId}
          engineName={engine?.name ?? engineId}
          environment={engine?.environment}
          task={modal.task}
          pending={action.isPending}
          problem={problem}
          onConfirm={dispatch}
          onClose={() => {
            setModal(null)
          }}
        />
      )}
    </div>
  )
}

interface RowProps {
  task: TaskDto
  gate: ReturnType<typeof actionGate>
  onReassign: () => void
  onReturn: () => void
}

function TaskRow({ task, gate, onReassign, onReturn }: RowProps) {
  const active = (task.state ?? 'ACTIVE') === 'ACTIVE' && task.id !== undefined
  const assigned = task.assignee !== undefined
  return (
    <tr>
      <td className="ledger-name">
        {task.name ?? <span className="value-muted">(unnamed)</span>}
        {task.taskDefinitionKey !== undefined && (
          <code className="element-id"> {task.taskDefinitionKey}</code>
        )}
      </td>
      <td>
        <span className={`status-chip ${(task.state ?? 'active').toLowerCase()}`}>
          {task.state ?? 'ACTIVE'}
        </span>
      </td>
      <td>
        {task.assignee ?? <span className="value-muted">(unassigned)</span>}
        {task.owner !== undefined && task.owner !== task.assignee && (
          <span className="value-muted"> · owner {task.owner}</span>
        )}
      </td>
      <td>
        <Ts iso={task.createTime} relative />
      </td>
      <td>
        <Ts iso={task.dueDate} relative />
      </td>
      <td>
        {task.endTime !== undefined ? (
          <>
            <Ts iso={task.endTime} />
            {task.durationMs !== undefined &&
              ` (took ${formatSeconds(Math.round(task.durationMs / 1000))})`}
          </>
        ) : (
          <span className="value-muted">open</span>
        )}
      </td>
      <td className="task-actions">
        {active ? (
          <>
            {/* W2 #6 (T7): disabled task verbs carry the visible ActionHint gate naming
                the missing grant — never a title-only dead control. */}
            <span className="action-slot">
              <button
                type="button"
                className="row-action"
                disabled={!gate.enabled}
                aria-describedby={gate.enabled ? undefined : `reassign-hint-${task.id ?? ''}`}
                title={gate.enabled ? undefined : (gate.detail ?? gate.reason)}
                onClick={onReassign}
              >
                Reassign
              </button>
              {!gate.enabled && gate.reason !== undefined && (
                <ActionHint id={`reassign-hint-${task.id ?? ''}`} text={gate.reason} tone="gate" />
              )}
            </span>
            <span className="action-slot">
              <button
                type="button"
                className="row-action"
                disabled={!gate.enabled || !assigned}
                aria-describedby={
                  !gate.enabled || !assigned ? `return-hint-${task.id ?? ''}` : undefined
                }
                title={
                  !gate.enabled
                    ? (gate.detail ?? gate.reason)
                    : !assigned
                      ? 'already unassigned — nothing to return'
                      : undefined
                }
                onClick={onReturn}
              >
                Return to team
              </button>
              {!gate.enabled && gate.reason !== undefined ? (
                <ActionHint id={`return-hint-${task.id ?? ''}`} text={gate.reason} tone="gate" />
              ) : (
                !assigned && (
                  <ActionHint
                    id={`return-hint-${task.id ?? ''}`}
                    text="Blocked: already unassigned"
                    tone="gate"
                  />
                )
              )}
            </span>
          </>
        ) : (
          <span className="value-muted">—</span>
        )}
      </td>
    </tr>
  )
}

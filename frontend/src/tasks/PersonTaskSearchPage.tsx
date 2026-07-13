import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import type { ActionRequest } from '../api/actions'
import { useInstanceAction } from '../api/actions'
import type { PersonTaskRow, TaskDto } from '../api/model'
import { runPersonTaskSearch } from '../api/queries'
import { useEngines } from '../api/useEngines'
import { roleOn, useMe } from '../api/me'
import type { MeDto } from '../api/me'
import { actionGate, VERBS } from '../actions/catalog'
import { TaskAssignModal } from '../actions/TaskAssignModal'
import type { TaskAssignMode } from '../actions/TaskAssignModal'
import type { ActionProblem } from '../actions/problem'
import { ActionHint } from '../components/ActionHint'
import { PartialResultsBanner } from '../components/PartialResultsBanner'
import { useToast } from '../components/toast'
import { formatCount } from '../lib/format'
import { Ts } from '../lib/Ts'
import { summarizePartials } from '../search/partials'

interface ModalState {
  mode: TaskAssignMode
  row: PersonTaskRow
}

/**
 * Person-centric task search (issue #99): "Bob is on vacation, what is he sitting on?" — every
 * OPEN task assigned to, or claimable by, a person across every readable engine. Rows feed the
 * EXISTING reassign/return-to-team verbs (SPEC §5) unchanged; this page only finds the targets.
 * Search state lives in the URL (`?person=`), mirroring /search's shareable-link convention. ONE
 * modal + mutation hook for the whole page (mirrors TasksTab), parameterized by whichever row's
 * "Reassign"/"Return to team" was clicked — every row targets a different engine/instance, so
 * the hook is (re)created against the modal's current target, never a fixed route pair.
 */
export function PersonTaskSearchPage() {
  const [params, setParams] = useSearchParams()
  const urlPerson = params.get('person') ?? ''
  const [draft, setDraft] = useState(urlPerson)
  const engines = useEngines()
  const me = useMe()
  const toast = useToast()

  const query = useQuery({
    queryKey: ['personTasks', urlPerson],
    queryFn: () => runPersonTaskSearch(urlPerson),
    enabled: urlPerson !== '',
  })

  const [modal, setModal] = useState<ModalState | null>(null)
  const [problem, setProblem] = useState<ActionProblem | undefined>(undefined)
  const action = useInstanceAction(modal?.row.engineId ?? '', modal?.row.processInstanceId ?? '')

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const trimmed = draft.trim()
    setParams(trimmed === '' ? {} : { person: trimmed }, { replace: false })
  }

  const openModal = (mode: TaskAssignMode, row: PersonTaskRow) => {
    setProblem(undefined)
    setModal({ mode, row })
  }

  const dispatch = (verb: string, body: ActionRequest) => {
    action.mutate(
      { verb, body },
      {
        onSuccess: (result) => {
          setModal(null)
          setProblem(undefined)
          toast({ kind: 'success', text: result.deltaStatement ?? 'Done.' })
          void query.refetch()
        },
        onError: (error) => {
          setProblem(error.problem)
        },
      },
    )
  }

  const summary = summarizePartials(query.data?.perEngine)
  const modalEngine = (engines.data ?? []).find((e) => e.id === modal?.row.engineId)

  return (
    <div className="pane">
      <h2>Find a person’s tasks</h2>
      <form className="person-task-search-form" onSubmit={submit}>
        <label>
          Person (assignee or candidate user id)
          <input
            type="text"
            value={draft}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setDraft(event.target.value)
            }}
          />
        </label>
        <button type="submit" disabled={draft.trim() === ''}>
          Find tasks
        </button>
      </form>

      {urlPerson === '' && (
        <p className="zero-state">Enter a person to see what they’re sitting on.</p>
      )}

      {query.isPending && urlPerson !== '' && <div className="zero-state">Searching…</div>}

      {query.isError && (
        <div className="error-banner" role="alert">
          Search failed: {query.error.message}
        </div>
      )}

      {query.data !== undefined && (
        <>
          <PartialResultsBanner
            summary={summary}
            onRetry={() => {
              void query.refetch()
            }}
          />
          {(query.data.rows ?? []).length === 0 ? (
            <p className="zero-state">
              {summary.okEngines === 0 && summary.totalEngines > 0
                ? 'No engines answered — nothing to show.'
                : `No open tasks assigned to, or claimable by, "${urlPerson}".`}
            </p>
          ) : (
            <table className="ledger-table">
              <caption className="visually-hidden">
                {formatCount((query.data.rows ?? []).length)} tasks for {urlPerson}
              </caption>
              <thead>
                <tr>
                  <th scope="col">Task</th>
                  <th scope="col">Engine</th>
                  <th scope="col">Process</th>
                  <th scope="col">Match</th>
                  <th scope="col">Created</th>
                  <th scope="col">Due</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {(query.data.rows ?? []).map((row, index) => (
                  <PersonTaskRowView
                    key={row.taskId ?? `row-${String(index)}`}
                    row={row}
                    engineName={
                      (engines.data ?? []).find((e) => e.id === row.engineId)?.name ??
                      row.engineId ??
                      ''
                    }
                    environment={
                      (engines.data ?? []).find((e) => e.id === row.engineId)?.environment
                    }
                    engineMode={(engines.data ?? []).find((e) => e.id === row.engineId)?.mode}
                    me={me.data}
                    onReassign={() => {
                      openModal('reassign', row)
                    }}
                    onReturn={() => {
                      openModal('return', row)
                    }}
                  />
                ))}
              </tbody>
            </table>
          )}
        </>
      )}

      {modal !== null && (
        <TaskAssignModal
          mode={modal.mode}
          engineId={modal.row.engineId ?? ''}
          instanceId={modal.row.processInstanceId ?? ''}
          engineName={modalEngine?.name ?? modal.row.engineId ?? ''}
          environment={modalEngine?.environment}
          task={toTaskDto(modal.row)}
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

function toTaskDto(row: PersonTaskRow): TaskDto {
  return {
    id: row.taskId,
    name: row.taskName,
    taskDefinitionKey: row.taskDefinitionKey,
    assignee: row.assignee,
  }
}

interface RowProps {
  row: PersonTaskRow
  engineName: string
  environment: string | undefined
  engineMode: string | undefined
  me: MeDto | undefined
  onReassign: () => void
  onReturn: () => void
}

function PersonTaskRowView({
  row,
  engineName,
  environment,
  engineMode,
  me,
  onReassign,
  onReturn,
}: RowProps) {
  const engineId = row.engineId ?? ''
  const instanceId = row.processInstanceId ?? ''
  const roleHint = roleOn(me, engineId)

  const gate = actionGate({
    meta: VERBS.reassignTask,
    roleHint,
    engineMode,
    environment,
  })

  const assigned = row.assignee !== undefined

  return (
    <tr>
      <td className="ledger-name">
        {row.taskName ?? <span className="value-muted">(unnamed)</span>}
        {row.taskDefinitionKey !== undefined && (
          <code className="element-id"> {row.taskDefinitionKey}</code>
        )}
      </td>
      <td>{engineName}</td>
      <td>
        {row.processDefinitionKey ?? <span className="value-muted">—</span>}
        {instanceId !== '' && <code className="element-id"> {instanceId}</code>}
      </td>
      <td>
        <span className={`status-chip ${(row.matchReason ?? 'assigned').toLowerCase()}`}>
          {row.matchReason === 'CANDIDATE' ? 'Claimable' : 'Assigned'}
        </span>
      </td>
      <td>
        <Ts iso={row.createTime} relative />
      </td>
      <td>
        <Ts iso={row.dueDate} relative />
      </td>
      <td className="task-actions">
        <span className="action-slot">
          <button
            type="button"
            className="row-action"
            disabled={!gate.enabled}
            aria-describedby={gate.enabled ? undefined : `person-reassign-hint-${row.taskId ?? ''}`}
            title={gate.enabled ? undefined : (gate.detail ?? gate.reason)}
            onClick={onReassign}
          >
            Reassign
          </button>
          {!gate.enabled && gate.reason !== undefined && (
            <ActionHint
              id={`person-reassign-hint-${row.taskId ?? ''}`}
              text={gate.reason}
              tone="gate"
            />
          )}
        </span>
        <span className="action-slot">
          <button
            type="button"
            className="row-action"
            disabled={!gate.enabled || !assigned}
            aria-describedby={
              !gate.enabled || !assigned ? `person-return-hint-${row.taskId ?? ''}` : undefined
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
          {gate.enabled && !assigned && (
            <ActionHint
              id={`person-return-hint-${row.taskId ?? ''}`}
              text="Blocked: already unassigned"
              tone="gate"
            />
          )}
        </span>
      </td>
    </tr>
  )
}

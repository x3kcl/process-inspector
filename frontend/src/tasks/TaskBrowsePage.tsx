import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, ApiError } from '../api/client'
import { submitBulk } from '../api/bulk'
import type { components } from '../api/schema'
import { useToast } from '../components/toast'
import { problemBanner } from '../actions/problem'
import { ActionError } from '../api/actions'

type TaskBrowsePageDto = components['schemas']['TaskBrowsePage']
type TaskRow = components['schemas']['TaskRow']

/** Open-task browse. Person search at /tasks stays. Bulk reassign cap is 200. */
export function TaskBrowsePage() {
  const [definitionKey, setDefinitionKey] = useState('')
  const [assigneeFilter, setAssigneeFilter] = useState('')
  const [candidateGroup, setCandidateGroup] = useState('')
  const [unassigned, setUnassigned] = useState(false)
  const [overdue, setOverdue] = useState(true)
  const [picked, setPicked] = useState<Record<string, TaskRow>>({})
  const [newAssignee, setNewAssignee] = useState('')
  const [reason, setReason] = useState('')
  const toast = useToast()
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['task-browse', definitionKey, assigneeFilter, candidateGroup, unassigned, overdue],
    queryFn: async (): Promise<TaskBrowsePageDto> => {
      const { data, error, response } = await api.GET('/api/tasks/browse', {
        params: {
          query: {
            processDefinitionKey: definitionKey || undefined,
            assignee: assigneeFilter || undefined,
            candidateGroup: candidateGroup || undefined,
            unassigned,
            overdue,
          },
        },
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const bulk = useMutation({
    mutationFn: submitBulk,
    onSuccess: (job) => {
      setPicked({})
      toast({ kind: 'success', text: `Bulk ${job.id ?? ''} queued.` })
      void queryClient.invalidateQueries({ queryKey: ['task-browse'] })
    },
  })
  const rows = query.data?.rows ?? []
  const selected = Object.values(picked)
  const reasonOk = reason.trim().length >= 10
  const canReassign =
    selected.length > 0 && selected.length <= 200 && reasonOk && newAssignee.trim() !== ''
  const canUnassign = selected.length > 0 && selected.length <= 200 && reasonOk

  const toggle = (row: TaskRow) => {
    const key = `${row.engineId ?? ''}:${row.taskId ?? ''}`
    setPicked((current) => {
      if (key in current) {
        return Object.fromEntries(Object.entries(current).filter(([entry]) => entry !== key))
      }
      return { ...current, [key]: row }
    })
  }

  const submit = (verb: 'reassign-task' | 'unassign-task') => {
    bulk.mutate({
      verb,
      reason: reason.trim(),
      ...(verb === 'reassign-task' ? { assignee: newAssignee.trim() } : {}),
      items: selected.map((row) => ({
        engineId: row.engineId ?? '',
        instanceId: row.processInstanceId || row.taskId || '',
        jobId: row.taskId ?? '',
      })),
    })
  }

  return (
    <section>
      <h1>Task browser</h1>
      <p>Open tasks across engines. Find-by-person stays on Find tasks.</p>
      <form
        onSubmit={(event) => {
          event.preventDefault()
        }}
      >
        <label>
          Definition key{' '}
          <input
            value={definitionKey}
            onChange={(event) => {
              setDefinitionKey(event.target.value)
            }}
          />
        </label>
        <label>
          Assignee{' '}
          <input
            value={assigneeFilter}
            onChange={(event) => {
              setAssigneeFilter(event.target.value)
            }}
          />
        </label>
        <label>
          Candidate group{' '}
          <input
            value={candidateGroup}
            onChange={(event) => {
              setCandidateGroup(event.target.value)
            }}
          />
        </label>
        <label>
          <input
            type="checkbox"
            checked={unassigned}
            onChange={(event) => {
              setUnassigned(event.target.checked)
            }}
          />{' '}
          Unassigned
        </label>
        <label>
          <input
            type="checkbox"
            checked={overdue}
            onChange={(event) => {
              setOverdue(event.target.checked)
            }}
          />{' '}
          Overdue
        </label>
      </form>
      {(query.data?.cappedEngines ?? []).length > 0 && (
        <p>Lower bound. Capped or unread engines: {(query.data?.cappedEngines ?? []).join(', ')}</p>
      )}
      {query.isError && <p role="alert">{query.error.message}</p>}
      <table>
        <thead>
          <tr>
            <th />
            <th>Engine</th>
            <th>Task</th>
            <th>Assignee</th>
            <th>Due</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = `${row.engineId ?? ''}:${row.taskId ?? ''}`
            return (
              <tr key={key}>
                <td>
                  <input
                    type="checkbox"
                    checked={key in picked}
                    aria-label={`Select ${row.taskId ?? ''}`}
                    onChange={() => {
                      toggle(row)
                    }}
                  />
                </td>
                <td>{row.engineId}</td>
                <td>
                  {row.name} <code>{row.taskId}</code>
                </td>
                <td>{row.assignee ?? '(unassigned)'}</td>
                <td>{row.dueDate ?? ''}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <p>{selected.length} selected (cap 200).</p>
      <label>
        New assignee{' '}
        <input
          value={newAssignee}
          onChange={(event) => {
            setNewAssignee(event.target.value)
          }}
        />
      </label>
      <label>
        Reason{' '}
        <input
          value={reason}
          onChange={(event) => {
            setReason(event.target.value)
          }}
        />
      </label>
      <button
        type="button"
        disabled={!canReassign || bulk.isPending}
        onClick={() => {
          submit('reassign-task')
        }}
      >
        Reassign selected
      </button>
      <button
        type="button"
        disabled={!canUnassign || bulk.isPending}
        onClick={() => {
          submit('unassign-task')
        }}
      >
        Return selected to team
      </button>
      {bulk.isError && (
        <p role="alert">
          {bulk.error instanceof ActionError
            ? problemBanner(bulk.error.problem)
            : bulk.error.message}
        </p>
      )}
    </section>
  )
}

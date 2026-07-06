import type { TaskDto } from '../../api/model'
import { formatDateTime, formatSeconds } from '../../lib/format'
import { useInstanceTasks } from '../useInstanceQueries'

interface Props {
  engineId: string
  instanceId: string
}

/**
 * User tasks, completed AND open, one ledger (SPEC §4) — bound to the historic ∪ runtime
 * union of GET …/tasks. Complete/reassign actions arrive with the M4 action toolbar.
 */
export default function TasksTab({ engineId, instanceId }: Props) {
  const query = useInstanceTasks(engineId, instanceId)

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
          </tr>
        </thead>
        <tbody>
          {tasks.map((task, index) => (
            <TaskRow key={task.id ?? `row-${String(index)}`} task={task} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function TaskRow({ task }: { task: TaskDto }) {
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
      <td>{formatDateTime(task.createTime)}</td>
      <td>{formatDateTime(task.dueDate)}</td>
      <td>
        {task.endTime !== undefined ? (
          <>
            {formatDateTime(task.endTime)}
            {task.durationMs !== undefined &&
              ` (took ${formatSeconds(Math.round(task.durationMs / 1000))})`}
          </>
        ) : (
          <span className="value-muted">open</span>
        )}
      </td>
    </tr>
  )
}

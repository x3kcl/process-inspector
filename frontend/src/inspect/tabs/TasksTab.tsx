import { PendingContract } from '../PendingContract'

export default function TasksTab() {
  return (
    <PendingContract
      promise="Open user tasks: assignee, candidate groups, create time and due date, with complete-task wired to the M4 action rails."
      endpoint="GET /api/instances/{engineId}/{instanceId}/tasks"
    />
  )
}

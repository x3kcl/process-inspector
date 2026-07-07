// Pure wire-payload construction for the task reassign / return-to-team verbs (v1.x #6).
// Kept out of the modal component so the exact body that crosses the wire is unit-testable
// under the node-env vitest (no DOM). The cURL of this same body is server-computed and
// verified in the backend ActionCurlTest.
import type { ActionRequest } from '../api/actions'

export type TaskAssignMode = 'reassign' | 'return'

export function taskAssignVerb(mode: TaskAssignMode): string {
  return mode === 'reassign' ? 'reassign-task' : 'unassign-task'
}

/**
 * The exact body the modal POSTs. {@code taskId} always; {@code assignee} only when
 * reassigning (trimmed — a whitespace-only id is treated as empty and the modal keeps the
 * confirm disabled); {@code reason} only when non-empty (trimmed). Return-to-team never
 * carries an assignee — clearing it is the whole point.
 */
export function buildTaskAssignBody(
  mode: TaskAssignMode,
  taskId: string,
  assignee: string,
  reason: string,
): ActionRequest {
  const trimmedAssignee = assignee.trim()
  const trimmedReason = reason.trim()
  return {
    taskId,
    ...(mode === 'reassign' ? { assignee: trimmedAssignee } : {}),
    ...(trimmedReason === '' ? {} : { reason: trimmedReason }),
  }
}

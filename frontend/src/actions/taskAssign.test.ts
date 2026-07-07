import { describe, expect, it } from 'vitest'
import { buildTaskAssignBody, taskAssignVerb } from './taskAssign'

describe('taskAssignVerb', () => {
  it('maps each mode to its backend verb', () => {
    expect(taskAssignVerb('reassign')).toBe('reassign-task')
    expect(taskAssignVerb('return')).toBe('unassign-task')
  })
})

describe('buildTaskAssignBody', () => {
  it('reassign carries the trimmed assignee', () => {
    expect(buildTaskAssignBody('reassign', 'task-9', '  gonzo  ', '')).toEqual({
      taskId: 'task-9',
      assignee: 'gonzo',
    })
  })

  it('reassign includes a trimmed reason when present', () => {
    expect(buildTaskAssignBody('reassign', 'task-9', 'gonzo', '  handing off shift  ')).toEqual({
      taskId: 'task-9',
      assignee: 'gonzo',
      reason: 'handing off shift',
    })
  })

  it('return-to-team never carries an assignee — clearing it is the point', () => {
    const body = buildTaskAssignBody('return', 'task-9', 'ignored-in-return-mode', 'end of shift')
    expect(body).toEqual({ taskId: 'task-9', reason: 'end of shift' })
    expect(body).not.toHaveProperty('assignee')
  })

  it('omits an empty / whitespace-only reason rather than sending an empty string', () => {
    expect(buildTaskAssignBody('return', 'task-9', '', '   ')).toEqual({ taskId: 'task-9' })
  })

  it('reassign to a whitespace-only id collapses to an empty assignee (confirm stays disabled)', () => {
    expect(buildTaskAssignBody('reassign', 'task-9', '   ', '')).toEqual({
      taskId: 'task-9',
      assignee: '',
    })
  })
})

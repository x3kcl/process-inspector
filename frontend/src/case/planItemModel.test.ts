import { describe, expect, it } from 'vitest'
import type { CasePlanItem } from '../api/model'
import { describeLiveState, flattenPlanItems, phaseOf } from './planItemModel'

function planItem(over: Partial<CasePlanItem>): CasePlanItem {
  return {
    id: 'pi',
    elementId: 'el',
    stage: false,
    state: 'active',
    ...over,
  }
}

describe('flattenPlanItems', () => {
  it('nests a stage child one level under its stage and orders siblings by createTime', () => {
    const stage = planItem({
      id: 'stage-1',
      elementId: 'stage',
      stage: true,
      createTime: '2026-01-01T00:00:00Z',
    })
    const childA = planItem({
      id: 'a',
      elementId: 'taskA',
      stageInstanceId: 'stage-1',
      createTime: '2026-01-01T00:00:02Z',
    })
    const childB = planItem({
      id: 'b',
      elementId: 'taskB',
      stageInstanceId: 'stage-1',
      createTime: '2026-01-01T00:00:01Z',
    })
    const top = planItem({ id: 'top', elementId: 'topTask', createTime: '2026-01-01T00:00:00Z' })

    const rows = flattenPlanItems([childA, stage, childB, top])

    // stage before its children; childB before childA (createTime); stage nested at depth 1.
    expect(rows.map((r) => r.item.elementId)).toEqual(['stage', 'taskB', 'taskA', 'topTask'])
    expect(rows.find((r) => r.item.elementId === 'taskB')?.depth).toBe(1)
    expect(rows.find((r) => r.item.elementId === 'stage')?.depth).toBe(0)
    expect(rows.find((r) => r.item.elementId === 'topTask')?.depth).toBe(0)
  })

  it('treats a plan item whose stageInstanceId points at nothing as a root (never dropped)', () => {
    const orphan = planItem({ id: 'o', elementId: 'orphanTask', stageInstanceId: 'gone' })
    const rows = flattenPlanItems([orphan])
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ depth: 0 })
  })

  it('does not hang on a stageInstanceId cycle', () => {
    const a = planItem({ id: 'a', elementId: 'A', stageInstanceId: 'b', stage: true })
    const b = planItem({ id: 'b', elementId: 'B', stageInstanceId: 'a', stage: true })
    const rows = flattenPlanItems([a, b])
    // both appear exactly once — the visited-guard breaks the cycle
    expect(rows).toHaveLength(2)
  })
})

describe('describeLiveState', () => {
  it('renders FAILED and RETRYING as pattern + text, null otherwise', () => {
    expect(describeLiveState('FAILED')).toMatchObject({ label: 'Failed' })
    expect(describeLiveState('RETRYING')).toMatchObject({ label: 'Retrying' })
    expect(describeLiveState(null)).toBeNull()
    expect(describeLiveState(undefined)).toBeNull()
  })
})

describe('phaseOf', () => {
  it('maps engine states to a coarse phase', () => {
    expect(phaseOf('active')).toBe('active')
    expect(phaseOf('async-active')).toBe('active')
    expect(phaseOf('completed')).toBe('ended')
    expect(phaseOf('terminated')).toBe('ended')
    expect(phaseOf('available')).toBe('available')
    expect(phaseOf(undefined)).toBe('available')
  })
})

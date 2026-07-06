import { describe, expect, it } from 'vitest'
import type { InstanceHierarchy } from '../api/model'
import { cascadeVictims } from './cascade'

const TREE: InstanceHierarchy = {
  root: {
    processInstanceId: 'root-1',
    children: [
      {
        processInstanceId: 'target-2',
        children: [
          { processInstanceId: 'child-a', businessKey: 'order-1' },
          { processInstanceId: 'child-b', ended: true },
          {
            processInstanceId: 'child-c',
            children: [{ processInstanceId: 'grandchild-d' }],
          },
        ],
      },
    ],
  },
}

describe('cascadeVictims', () => {
  it('collects live descendants of the target, transitively, with business keys', () => {
    expect(cascadeVictims(TREE, 'target-2')).toEqual([
      'child-a (order-1)',
      'child-c',
      'grandchild-d',
    ])
  })

  it('excludes ended children — history is not a cascade victim', () => {
    const victims = cascadeVictims(TREE, 'target-2')
    expect(victims).not.toContain('child-b')
  })

  it('returns an empty list for a leaf — it dies alone', () => {
    expect(cascadeVictims(TREE, 'grandchild-d')).toEqual([])
  })

  it('answers unavailable when the target is not in the tree', () => {
    expect(cascadeVictims({}, 'target-2')).toBe('unavailable')
    expect(cascadeVictims(TREE, 'missing')).toBe('unavailable')
  })

  it('answers unavailable rather than enumerate a truncated branch', () => {
    const truncated: InstanceHierarchy = {
      root: {
        processInstanceId: 'target',
        childrenTruncated: true,
        children: [{ processInstanceId: 'visible-child' }],
      },
    }
    expect(cascadeVictims(truncated, 'target')).toBe('unavailable')
  })
})

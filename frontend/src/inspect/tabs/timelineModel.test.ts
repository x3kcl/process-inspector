import { describe, expect, it } from 'vitest'
import type { TimelineActivity } from '../../api/model'
import { describeLiveState, flattenTimeline, isPhantom, MAX_RENDER_DEPTH } from './timelineModel'

const act = (over: Partial<TimelineActivity>): TimelineActivity => ({
  activityId: 'a',
  activityType: 'serviceTask',
  startTime: '2026-07-07T00:00:00.000Z',
  ...over,
})

const ids = (rows: ReturnType<typeof flattenTimeline>): string[] =>
  rows.map((r) =>
    r.kind === 'cap-warning' ? `cap@${String(r.depth)}` : (r.activity.activityId ?? '?'),
  )

describe('flattenTimeline', () => {
  it('flattens the tree pre-order (parent above its indented children) with depth tags', () => {
    const rows = flattenTimeline([
      act({ activityId: 'start' }),
      act({
        activityId: 'callPayment',
        activityType: 'callActivity',
        calledProcessInstanceId: 'child',
        children: [act({ activityId: 'childStart' }), act({ activityId: 'childCharge' })],
      }),
      act({ activityId: 'end' }),
    ])

    expect(ids(rows)).toEqual(['start', 'callPayment', 'childStart', 'childCharge', 'end'])

    const depthOf = (id: string): number | undefined =>
      rows.find((r) => r.kind !== 'cap-warning' && r.activity.activityId === id)?.depth
    expect(depthOf('callPayment')).toBe(0)
    expect(depthOf('childStart')).toBe(1)
    expect(depthOf('end')).toBe(0)
  })

  it('emits a server-cap warning row at child depth after a capped node', () => {
    const rows = flattenTimeline([
      act({ activityId: 'callPayment', isCapped: true, children: [act({ activityId: 'c1' })] }),
      act({ activityId: 'end' }),
    ])

    expect(ids(rows)).toEqual(['callPayment', 'c1', 'cap@1', 'end'])
    const cap = rows.find((r) => r.kind === 'cap-warning')
    expect(cap?.kind === 'cap-warning' && cap.reason).toBe('server-cap')
  })

  it('classifies a node with no start AND no end as a phantom', () => {
    const phantom = { activityId: 'charge', liveJobState: 'FAILED' } satisfies TimelineActivity
    expect(isPhantom(phantom)).toBe(true)
    expect(isPhantom(act({ endTime: undefined }))).toBe(false) // unfinished but real (has a start)

    const rows = flattenTimeline([phantom])
    expect(rows[0].kind).toBe('phantom')
  })

  it('stops recursing past MAX_RENDER_DEPTH — the recursive guardrail terminates', () => {
    let node: TimelineActivity = act({ activityId: 'leaf' })
    for (let i = 0; i < MAX_RENDER_DEPTH + 3; i++) {
      node = act({ activityId: `n${String(i)}`, children: [node] })
    }

    const rows = flattenTimeline([node]) // must not blow the stack
    const guard = rows.find((r) => r.kind === 'cap-warning' && r.reason === 'render-depth')
    expect(guard).toBeTruthy()
    expect(Math.max(...rows.map((r) => r.depth))).toBeLessThanOrEqual(MAX_RENDER_DEPTH + 1)
  })
})

describe('describeLiveState', () => {
  it('maps each live state to a non-hue icon + label + PATTERN class', () => {
    expect(describeLiveState('FAILED')).toEqual({
      icon: '🛑',
      label: 'FAILED',
      barClass: 'tl-bar--failed',
    })
    expect(describeLiveState('RETRYING')).toEqual({
      icon: '⚠',
      label: 'RETRYING',
      barClass: 'tl-bar--retrying',
    })
  })

  it('is null for a healthy node (no annotation)', () => {
    expect(describeLiveState(undefined)).toBeNull()
  })
})

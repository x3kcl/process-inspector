import { describe, expect, it } from 'vitest'
import type { TimelineActivity } from '../../api/model'
import {
  describeLiveState,
  durationShare,
  flattenTimeline,
  foldFlows,
  instanceSpanMs,
  isPhantom,
  MAX_RENDER_DEPTH,
} from './timelineModel'

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

describe('#675 share, span and flow folding', () => {
  const T0 = Date.parse('2026-07-07T00:00:00.000Z')
  const closed = [
    act({ endTime: '2026-07-07T00:00:01.000Z', durationMs: 1000 }),
    act({ startTime: '2026-07-07T00:00:01.000Z', endTime: '2026-07-07T00:00:10.000Z' }),
  ]

  it('share is the row duration over the instance span, clamped to 100', () => {
    expect(durationShare(1000, 10_000)).toBe(10)
    expect(durationShare(50_000, 10_000)).toBe(100)
  })

  it('share is null for a zero-length instance, a running row, or no duration', () => {
    expect(durationShare(0, 0)).toBeNull()
    expect(durationShare(undefined, 10_000)).toBeNull()
    expect(durationShare(null, 10_000)).toBeNull()
  })

  it('a capped row keeps its own share (isCapped concerns its children only)', () => {
    const [row] = flattenTimeline([act({ isCapped: true, durationMs: 2000 })])
    expect(row.kind === 'activity' && durationShare(row.activity.durationMs, 10_000)).toBe(20)
  })

  it('span ends at the last end for a finished instance, ignoring now', () => {
    expect(instanceSpanMs(flattenTimeline(closed), T0 + 86_400_000)).toBe(10_000)
  })

  it('span runs to now while a row is still open; zero-length instance is 0', () => {
    const open = flattenTimeline([act({})])
    expect(instanceSpanMs(open, T0 + 5000)).toBe(5000)
    expect(instanceSpanMs(flattenTimeline([act({ endTime: act({}).startTime })]), T0 + 9)).toBe(0)
  })

  it('folds sequenceFlow rows by default and counts them; shows all on request', () => {
    const rows = flattenTimeline([
      act({ activityId: 'a' }),
      act({ activityId: 'f1', activityType: 'sequenceFlow' }),
      act({ activityId: 'f2', activityType: 'sequenceFlow' }),
    ])
    const folded = foldFlows(rows, false)
    expect(ids(folded.rows)).toEqual(['a'])
    expect(folded.hidden).toBe(2)
    const all = foldFlows(rows, true)
    expect(all.rows).toHaveLength(3)
    expect(all.hidden).toBe(0)
  })
})

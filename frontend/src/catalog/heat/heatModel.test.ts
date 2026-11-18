import { describe, expect, it } from 'vitest'
import { badgeText, heatMarkers, honestyLines, refusalMessage, HEAT_PRESETS } from './heatModel'

const task = (over = {}) => ({
  activityId: 'review',
  name: 'Review',
  type: 'userTask',
  executions: 10,
  instances: 10,
  hitPct: 62,
  armedNotTaken: 0,
  avgMs: 1234,
  p50Ms: 900,
  p95Ms: 3000,
  maxMs: 4000,
  totalMs: 12340,
  sharePct: 55,
  tier: 'DOMINANT' as const,
  ...over,
})

describe('badgeText', () => {
  it('carries average, hit rate and the tier word', () => {
    expect(badgeText(task())).toBe('avg 1.2 s · 62 % · dominant')
  })
  it('says armed in N, taken in M where a node was armed and not taken', () => {
    const node = task({
      type: 'intermediateCatchEvent',
      instances: 1,
      armedNotTaken: 3,
      tier: 'NONE',
    })
    expect(badgeText(node)).toContain('armed in 4, taken in 1')
  })
})

describe('heatMarkers', () => {
  it('badges tasks, sub-processes and call activities by tier; not events or gateways', () => {
    const { sets, texts } = heatMarkers([
      task(),
      task({ activityId: 'sub', type: 'subProcess', tier: 'WARM' }),
      task({ activityId: 'call', type: 'callActivity', tier: 'COOL' }),
      task({ activityId: 'gw', type: 'exclusiveGateway' }),
      task({ activityId: 'end', type: 'endEvent' }),
    ])
    expect(sets.heatDominantActivityIds).toEqual(['review'])
    expect(sets.heatWarmActivityIds).toEqual(['sub'])
    expect(sets.heatCoolActivityIds).toEqual(['call'])
    expect(texts.heatDominantActivityIds?.get('review')).toBe('avg 1.2 s · 62 % · dominant')
  })
  it('badges an event that was armed and not taken', () => {
    const { sets } = heatMarkers([
      task({
        activityId: 'catchB',
        type: 'intermediateCatchEvent',
        armedNotTaken: 2,
        tier: 'NONE',
      }),
    ])
    expect(sets.heatCoolActivityIds).toEqual(['catchB'])
  })
})

describe('honestyLines', () => {
  const base = {
    coverage: 'FULL' as const,
    structure: 'RESOLVED' as const,
    sampledInstances: 150,
    finishedTotal: 1000,
    droppedInstances: 0,
    dropReasons: { tooManyRows: 0, readFailed: 0, noActivityHistory: 0 },
    asOf: '2026-11-18T10:00:00Z',
    samplingNote: 'newest 150 finished instances',
  }
  it('always states the sample size and the as-of time', () => {
    const text = honestyLines(base).join('\n')
    expect(text).toContain('sampled 150 of 1,000 finished instances')
    expect(text).toContain('as of')
  })
  it('names what was dropped and why', () => {
    const text = honestyLines({
      ...base,
      droppedInstances: 5,
      dropReasons: { tooManyRows: 2, readFailed: 1, noActivityHistory: 2 },
    }).join('\n')
    expect(text).toContain('5 dropped')
    expect(text).toContain('2 too many activity rows')
    expect(text).toContain('1 unreadable')
    expect(text).toContain('2 without activity history')
  })
  it('says an engine without flow history records no route', () => {
    const text = honestyLines({ ...base, coverage: 'NO_FLOW_HISTORY' }).join('\n')
    expect(text).toContain('records no route')
  })
  it('says event-gateway branches are inferred only when the sampling note says so', () => {
    const inferred = honestyLines({
      ...base,
      coverage: 'NO_FLOW_HISTORY',
      samplingNote: 'x; event-gateway branches inferred from timing, this engine records no routes',
    }).join('\n')
    expect(inferred).toContain('event-gateway branches are inferred from timing')
    expect(honestyLines({ ...base, coverage: 'NO_FLOW_HISTORY' }).join('\n')).not.toContain(
      'inferred',
    )
  })
  it('says armed and taken events could not be told apart when the structure is unavailable', () => {
    const text = honestyLines({ ...base, structure: 'UNAVAILABLE' }).join('\n')
    expect(text).toContain('armed and taken events could not be told apart')
  })
  it('says a version without activity history records none, not zeros', () => {
    const text = honestyLines({ ...base, coverage: 'NO_ACTIVITY_HISTORY' }).join('\n')
    expect(text).toContain('records no activity history')
  })
  it('says no finished instances yet, not history off', () => {
    const text = honestyLines({
      ...base,
      coverage: 'NO_HISTORY',
      sampledInstances: 0,
      finishedTotal: 0,
    }).join('\n')
    expect(text).toContain('No finished instances yet')
    expect(text.toLowerCase()).not.toContain('history off')
  })
})

describe('refusalMessage', () => {
  const problem = (status: number, code: string) => ({ status, body: { code } })
  it.each([
    [404, 'definition-not-found', 'no longer exists'],
    [400, 'heat-bpmn-only', 'BPMN process definitions only'],
    [503, 'engine-unavailable', 'did not answer'],
    [503, 'heat-unavailable', 'could not be read'],
  ])('maps %s %s to its own plain message', (status, code, fragment) => {
    expect(refusalMessage(problem(status, code))).toContain(fragment)
  })
})

describe('presets', () => {
  it('offers 200, 500 and 1000 only', () => {
    expect(HEAT_PRESETS).toEqual([200, 500, 1000])
  })
})

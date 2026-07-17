// #236: the excluded-engine disclosure every coverage-claim sentence splices in.
import { describe, expect, it } from 'vitest'
import type { EngineDto } from '../api/model'
import { uncoveredClause, uncoveredEngines } from './coverage'

const active = (id: string): EngineDto => ({ id, lifecycle: 'active', reachable: true })

describe('uncoveredEngines (#236)', () => {
  it('a genuinely complete check yields NOTHING — no spurious "more excluded" tail', () => {
    const registered = [active('engine-a'), active('engine-b')]
    expect(uncoveredEngines(registered, ['engine-a', 'engine-b'])).toEqual([])
    expect(
      uncoveredClause(uncoveredEngines(registered, ['engine-a', 'engine-b']), 'searched'),
    ).toBe('')
  })

  it('names registered engines absent from the covered set, with a lifecycle reason', () => {
    const registered: EngineDto[] = [
      active('engine-a'),
      { id: 'engine-b', lifecycle: 'disabled' },
      { id: 'engine-c', lifecycle: 'probe_failed' },
    ]
    expect(uncoveredEngines(registered, ['engine-a'])).toEqual([
      { id: 'engine-b', reason: 'disabled' },
      { id: 'engine-c', reason: 'probe failed' },
    ])
  })

  it('an active-but-unreachable engine outside the check is glossed "currently unreachable"', () => {
    const registered: EngineDto[] = [active('engine-a'), { id: 'engine-down', reachable: false }]
    expect(uncoveredEngines(registered, ['engine-a'])).toEqual([
      { id: 'engine-down', reason: 'currently unreachable' },
    ])
  })

  it('an active reachable engine merely outside the scope carries no invented reason', () => {
    const registered = [active('engine-a'), active('engine-b')]
    expect(uncoveredEngines(registered, ['engine-a'])).toEqual([{ id: 'engine-b', reason: '' }])
  })

  it('tolerates an undefined registry list and id-less rows', () => {
    expect(uncoveredEngines(undefined, ['engine-a'])).toEqual([])
    expect(uncoveredEngines([{ name: 'no id yet' }], [])).toEqual([])
  })
})

describe('uncoveredClause (#236)', () => {
  it('splices count + names + reasons into one sentence-ready clause', () => {
    const clause = uncoveredClause(
      [
        { id: 'engine-b', reason: 'disabled' },
        { id: 'engine-d', reason: 'currently unreachable' },
      ],
      'searched',
    )
    expect(clause).toBe(
      ' (2 more registered engines not searched: engine-b [disabled], engine-d [currently unreachable] — see Engines)',
    )
  })

  it('singular form for one engine, bare id when there is no reason', () => {
    expect(uncoveredClause([{ id: 'engine-b', reason: '' }], 'checked')).toBe(
      ' (1 more registered engine not checked: engine-b — see Engines)',
    )
  })
})

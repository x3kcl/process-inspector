import { describe, expect, it } from 'vitest'
import type { ResolveMatch, ResolveResponse } from '../api/model'
import { classifyOmniboxInput, decideResolveNavigation, summarizeReachability } from './omnibox'

const ENGINES = ['engine-a', 'engine-b'] as const

describe('classifyOmniboxInput', () => {
  it('empty or whitespace input resolves to nothing', () => {
    expect(classifyOmniboxInput('', ENGINES)).toBeNull()
    expect(classifyOmniboxInput('   ', ENGINES)).toBeNull()
  })

  it('composite engine:id navigates to Stage 2 without a round trip', () => {
    expect(classifyOmniboxInput('engine-a:8f14e45f-ceea-4711', ENGINES)).toEqual({
      kind: 'inspect',
      engineId: 'engine-a',
      instanceId: '8f14e45f-ceea-4711',
    })
  })

  it('trims paste debris around the composite', () => {
    expect(classifyOmniboxInput('  engine-b: 42 ', ENGINES)).toEqual({
      kind: 'inspect',
      engineId: 'engine-b',
      instanceId: '42',
    })
  })

  it('a colon with an unknown prefix goes to the resolver, verbatim', () => {
    expect(classifyOmniboxInput('order:4711', ENGINES)).toEqual({
      kind: 'resolve',
      query: 'order:4711',
    })
  })

  it('a dangling composite (no id after the colon) goes to the resolver', () => {
    expect(classifyOmniboxInput('engine-a:', ENGINES)).toEqual({
      kind: 'resolve',
      query: 'engine-a:',
    })
  })

  it('plain input goes to the resolver (raw IDs and business keys alike)', () => {
    expect(classifyOmniboxInput('order-4711', ENGINES)).toEqual({
      kind: 'resolve',
      query: 'order-4711',
    })
  })
})

function match(overrides: Partial<ResolveMatch>): ResolveMatch {
  return {
    kind: 'PROCESS_INSTANCE',
    engineId: 'engine-a',
    processInstanceId: 'pi-1',
    compositeId: 'engine-a:pi-1',
    ...overrides,
  }
}

function response(matches: ResolveMatch[], perEngine?: ResolveResponse['perEngine']) {
  return { query: 'q', matches, perEngine } satisfies ResolveResponse
}

describe('decideResolveNavigation (R-SEM-04)', () => {
  it('exactly one ID-kind match navigates straight to Stage 2', () => {
    expect(decideResolveNavigation(response([match({ kind: 'TASK' })]))).toEqual({
      kind: 'navigate',
      engineId: 'engine-a',
      processInstanceId: 'pi-1',
    })
  })

  it('business-key matches become a pre-filtered search — NEVER an auto-navigate', () => {
    const decision = decideResolveNavigation(
      response([
        match({ kind: 'BUSINESS_KEY', businessKey: 'order-4711' }),
        match({ kind: 'BUSINESS_KEY', businessKey: 'order-4711', processInstanceId: 'pi-2' }),
      ]),
    )
    expect(decision).toEqual({ kind: 'search-business-key', businessKey: 'order-4711' })
  })

  it('even a SINGLE business-key match stays a search, not a navigation', () => {
    const decision = decideResolveNavigation(
      response([match({ kind: 'BUSINESS_KEY', businessKey: 'order-4711' })]),
    )
    expect(decision.kind).toBe('search-business-key')
  })

  it('the same id on several engines is an explicit disambiguation', () => {
    const matches = [match({}), match({ engineId: 'engine-b', compositeId: 'engine-b:pi-1' })]
    expect(decideResolveNavigation(response(matches))).toEqual({
      kind: 'disambiguate',
      matches,
    })
  })

  it('mixed kinds (an ID hit plus business-key hits) disambiguate too', () => {
    const matches = [
      match({ kind: 'PROCESS_INSTANCE' }),
      match({ kind: 'BUSINESS_KEY', processInstanceId: 'pi-2' }),
    ]
    expect(decideResolveNavigation(response(matches)).kind).toBe('disambiguate')
  })

  it('zero matches is an explicit not-found, never an error', () => {
    expect(decideResolveNavigation(response([]))).toEqual({ kind: 'not-found' })
  })
})

describe('summarizeReachability (R-SEM-12)', () => {
  it('counts reached engines and names the unreachable with their error', () => {
    const summary = summarizeReachability(
      response([], {
        'engine-a': { ok: true },
        'engine-down': { ok: false, error: 'timeout after 62000ms' },
      }),
    )
    expect(summary.reached).toBe(1)
    expect(summary.total).toBe(2)
    expect(summary.unreachable).toEqual([
      { engineId: 'engine-down', error: 'timeout after 62000ms' },
    ])
  })
})

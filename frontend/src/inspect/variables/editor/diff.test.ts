import { describe, expect, it } from 'vitest'
import { changeSentence, countLeaves, diffSummary, structuralDiff } from './diff'

describe('structuralDiff — values, never formatting', () => {
  it('finds a single changed leaf by path', () => {
    const changes = structuralDiff({ shipping: { cost: 0 }, note: 'x' }, { shipping: { cost: 12.5 }, note: 'x' })
    expect(changes).toEqual([
      { path: 'shipping.cost', kind: 'changed', before: 0, after: 12.5 },
    ])
  })
  it('reports key order and identical values as NO change (re-serialization noise)', () => {
    expect(structuralDiff({ a: 1, b: 2 }, { b: 2, a: 1 })).toEqual([])
  })
  it('reports added and removed keys', () => {
    const changes = structuralDiff({ a: 1 }, { b: 2 })
    expect(changes).toContainEqual({ path: 'a', kind: 'removed', before: 1 })
    expect(changes).toContainEqual({ path: 'b', kind: 'added', after: 2 })
  })
  it('walks arrays by index including length changes', () => {
    const changes = structuralDiff([1, 2], [1, 3, 4])
    expect(changes).toContainEqual({ path: '[1]', kind: 'changed', before: 2, after: 3 })
    expect(changes).toContainEqual({ path: '[2]', kind: 'added', after: 4 })
  })
  it('treats a structural type change as one leaf change', () => {
    const changes = structuralDiff({ a: { deep: true } }, { a: 5 })
    expect(changes).toEqual([{ path: 'a', kind: 'changed', before: { deep: true }, after: 5 }])
  })
})

describe('diffSummary — the §4a wording', () => {
  it('renders the canonical single-change line', () => {
    const doc = { shipping: { cost: 0 }, forty: Object.fromEntries(Array.from({ length: 39 }, (_, i) => [`f${String(i)}`, i])) }
    const next = { ...doc, shipping: { cost: 12.5 } }
    const changes = structuralDiff(doc, next)
    expect(diffSummary(changes, countLeaves(doc))).toBe(
      '1 of 40 fields changes: shipping.cost 0 → 12.5 — the other 39 are unchanged',
    )
  })
  it('says so when nothing changes', () => {
    expect(diffSummary([], 5)).toContain('no value change')
  })
})

describe('countLeaves', () => {
  it('counts scalar leaves through objects and arrays', () => {
    expect(countLeaves({ a: 1, b: { c: 2, d: [3, 4] } })).toBe(4)
    expect(countLeaves('scalar')).toBe(1)
    expect(countLeaves({})).toBe(1)
  })
})

describe('changeSentence — generated from the request object', () => {
  it('renders the §4a scalar sentence', () => {
    expect(
      changeSentence({
        name: 'orderTotal',
        before: 0,
        after: 149.9,
        typeLabel: 'number',
        scopeLabel: 'applies to the whole case',
        targetLabel: 'order-4711',
        engineName: 'billing-prod',
        environment: 'prod',
      }),
    ).toBe(
      'Change orderTotal from 0 to 149.9 (number, applies to the whole case) on order-4711 in billing-prod (PROD)',
    )
  })
  it('summarizes a single-leaf json edit inline', () => {
    const sentence = changeSentence({
      name: 'shipping',
      before: { cost: 0, mode: 'road' },
      after: { cost: 12.5, mode: 'road' },
      typeLabel: 'structured (json)',
      scopeLabel: 'applies to the whole case',
      targetLabel: 'order-4711',
      engineName: 'billing-test',
    })
    expect(sentence).toContain('cost 0 → 12.5')
  })
})

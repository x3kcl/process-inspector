// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { decisionKey, getLayoutDecision, recordLayoutDecision } from './viewLayoutDecisions'

beforeEach(() => {
  globalThis.localStorage.clear()
})
afterEach(() => {
  globalThis.localStorage.clear()
})

describe('viewLayoutDecisions (#197)', () => {
  it('has no decision for a key that was never recorded', () => {
    expect(getLayoutDecision(decisionKey('status=FAILED', new Set(['startTime'])))).toBeUndefined()
  })

  it('remembers a recorded decision', () => {
    const key = decisionKey('status=FAILED', new Set(['startTime']))
    recordLayoutDecision(key, 'applied')
    expect(getLayoutDecision(key)).toBe('applied')
  })

  it('a later decision for the same key overwrites the earlier one', () => {
    const key = decisionKey('status=FAILED', new Set(['startTime']))
    recordLayoutDecision(key, 'dismissed')
    recordLayoutDecision(key, 'applied')
    expect(getLayoutDecision(key)).toBe('applied')
  })

  it('the key depends on BOTH the search identity and the suggested set — different suggestions for the same view are asked separately', () => {
    const keyA = decisionKey('status=FAILED', new Set(['startTime']))
    const keyB = decisionKey('status=FAILED', new Set(['businessKey']))
    recordLayoutDecision(keyA, 'dismissed')
    expect(getLayoutDecision(keyB)).toBeUndefined()
  })

  it('column ordering in the suggested set does not change the key', () => {
    const keyA = decisionKey('status=FAILED', new Set(['startTime', 'businessKey']))
    const keyB = decisionKey('status=FAILED', new Set(['businessKey', 'startTime']))
    expect(keyA).toBe(keyB)
  })

  it('caps the stored entry count, evicting the oldest first', () => {
    for (let i = 0; i < 60; i++) {
      recordLayoutDecision(decisionKey(`status=v${String(i)}`, new Set(['startTime'])), 'applied')
    }
    // The very first key recorded is long since evicted...
    expect(getLayoutDecision(decisionKey('status=v0', new Set(['startTime'])))).toBeUndefined()
    // ...but a recent one survives.
    expect(getLayoutDecision(decisionKey('status=v59', new Set(['startTime'])))).toBe('applied')
  })

  it('survives corrupt localStorage content instead of throwing', () => {
    globalThis.localStorage.setItem('inspector.viewLayoutDecisions', '{not json')
    expect(getLayoutDecision(decisionKey('status=FAILED', new Set()))).toBeUndefined()
    expect(() => {
      recordLayoutDecision(decisionKey('status=FAILED', new Set()), 'applied')
    }).not.toThrow()
  })
})

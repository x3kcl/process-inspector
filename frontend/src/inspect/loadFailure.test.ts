// Usability W2 #4 (theme T12): honesty-copy convergence on the instance page. The
// not-found copy is COVERAGE-parameterized — definitive when the engine answered (404),
// hedged ONLY when coverage was partial (the engine never answered). The old copy hedged
// "or the engine may be unreachable" even when the engine had definitively said "no such
// instance", which contradicted the omnibox's definitive "resolved against 2 of 2 engines".
import { describe, expect, it } from 'vitest'
import { instanceLoadFailureCopy } from './loadFailure'

describe('instanceLoadFailureCopy (W2 #4, T12)', () => {
  it('404 = the engine ANSWERED: definitive not-found, no unreachable hedge', () => {
    const copy = instanceLoadFailureCopy(404)
    expect(copy).toMatch(/resolved against 1 of 1 engines/)
    expect(copy).toMatch(/confirmed/i)
    expect(copy).not.toMatch(/unreachable/i)
    expect(copy).not.toMatch(/may be/i)
  })

  it('gateway/unavailable statuses hedge — NOT a confirmed not-found', () => {
    for (const status of [502, 503, 504]) {
      const copy = instanceLoadFailureCopy(status)
      expect(copy).toMatch(/unreachable/i)
      expect(copy).toMatch(/NOT a confirmed not-found/)
    }
  })

  it('no status (network failure) hedges too', () => {
    expect(instanceLoadFailureCopy(undefined)).toMatch(/NOT a confirmed not-found/)
  })

  it('other refusals say the request failed without claiming anything about existence', () => {
    const copy = instanceLoadFailureCopy(403)
    expect(copy).toMatch(/says nothing about whether the instance exists/)
    expect(copy).not.toMatch(/confirmed not-found/i)
  })
})

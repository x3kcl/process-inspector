import { describe, expect, it } from 'vitest'
import { secondaryBadges, summarizePartials, zeroState } from './partials'

describe('summarizePartials', () => {
  it('reports a clean fan-out as no-partials', () => {
    const summary = summarizePartials({
      'engine-a': { ok: true, fetched: 10, total: 10 },
      'engine-b': { ok: true, fetched: 0, total: 0 },
    })
    expect(summary.lowerBound).toBe(false)
    expect(summary.failed).toEqual([])
    expect(summary.truncated).toEqual([])
    expect(summary.overflowing).toEqual([])
    expect(summary.okEngines).toBe(2)
  })

  it('collects engine failures with their error text', () => {
    const summary = summarizePartials({
      'engine-a': { ok: true, fetched: 3, total: 3 },
      'billing-prod': { ok: false, error: 'timeout' },
    })
    expect(summary.failed).toEqual([{ engineId: 'billing-prod', error: 'timeout' }])
    expect(summary.okEngines).toBe(1)
    expect(summary.totalEngines).toBe(2)
    expect(summary.lowerBound).toBe(true)
  })

  it('flags truncated DLQ/failing scans as lower bounds', () => {
    const summary = summarizePartials({
      'engine-a': { ok: true, fetched: 5, total: 5, dlqScan: 'truncated@5000' },
      'engine-b': { ok: true, fetched: 5, total: 5, failingScan: 'truncated@2000' },
    })
    expect(summary.truncated).toEqual([
      { engineId: 'engine-a', detail: 'dead-letter scan truncated@5000' },
      { engineId: 'engine-b', detail: 'failing-job scan truncated@2000' },
    ])
    expect(summary.lowerBound).toBe(true)
  })

  it('reports page-cap overflow (fetched < total) separately from errors', () => {
    const summary = summarizePartials({
      'orders-prod': { ok: true, fetched: 138, total: 2410 },
    })
    expect(summary.overflowing).toEqual([{ engineId: 'orders-prod', fetched: 138, total: 2410 }])
    expect(summary.failed).toEqual([])
    expect(summary.lowerBound).toBe(true)
  })
})

describe('zeroState', () => {
  it('is null while rows exist', () => {
    expect(zeroState({ rows: [{ compositeId: 'a:1' }], perEngine: {} })).toBeNull()
  })

  it('distinguishes all-engines-failed from a calm zero', () => {
    expect(zeroState({ rows: [], perEngine: { a: { ok: false, error: 'down' } } })).toBe(
      'all-engines-failed',
    )
  })

  it('never reports a confirmed zero under partial coverage', () => {
    expect(
      zeroState({
        rows: [],
        perEngine: { a: { ok: true, fetched: 0, total: 0 }, b: { ok: false, error: 'down' } },
      }),
    ).toBe('zero-under-partial-coverage')
  })

  it('reports a true zero only when every engine answered completely', () => {
    expect(zeroState({ rows: [], perEngine: { a: { ok: true, fetched: 0, total: 0 } } })).toBe(
      'true-zero',
    )
  })

  // #279: a stale-generation signature drill link must read as an honest reason, not a calm zero.
  it('surfaces a stale-generation signature filter instead of a true zero', () => {
    expect(
      zeroState({
        rows: [],
        perEngine: { a: { ok: true, fetched: 0, total: 0 } },
        signatureGeneration: {
          current: false,
          requestedAlgoVersion: 1,
          currentAlgoVersion: 2,
          reason: 'This link was built with error-signature generation v1 …',
        },
      }),
    ).toBe('stale-signature-generation')
  })

  it('lets a reachability caveat outrank the stale-generation advisory (both are true)', () => {
    // A down engine is a concrete "not a confirmed zero" — it ranks above the speculative
    // generation caveat so the operator sees the actionable reachability problem first.
    expect(
      zeroState({
        rows: [],
        perEngine: { a: { ok: false, error: 'down' }, b: { ok: true, fetched: 0, total: 0 } },
        signatureGeneration: {
          current: false,
          // legacy/unstamped link: requestedAlgoVersion omitted (NON_NULL on the wire)
          currentAlgoVersion: 2,
          reason: 'This link predates signature-generation stamping …',
        },
      }),
    ).toBe('zero-under-partial-coverage')
  })
})

describe('secondaryBadges', () => {
  it('is empty when the primary chip tells the whole story', () => {
    expect(
      secondaryBadges('FAILED', {
        ended: false,
        suspended: false,
        hasDeadLetterJobs: true,
        hasFailingJobs: false,
        failedInSubprocess: false,
      }),
    ).toEqual([])
  })

  it('marks the failed-in-subprocess roll-up', () => {
    expect(
      secondaryBadges('FAILED', {
        ended: false,
        suspended: false,
        hasDeadLetterJobs: false,
        hasFailingJobs: false,
        failedInSubprocess: true,
      }),
    ).toEqual(['in subprocess'])
  })

  it('surfaces the suspended+dead-letter collision the precedence ladder hides', () => {
    expect(
      secondaryBadges('FAILED', {
        ended: false,
        suspended: true,
        hasDeadLetterJobs: true,
        hasFailingJobs: false,
        failedInSubprocess: false,
      }),
    ).toEqual(['suspended'])
  })

  it('badges dead-letter jobs on a COMPLETED parent', () => {
    expect(
      secondaryBadges('COMPLETED', {
        ended: true,
        suspended: false,
        hasDeadLetterJobs: true,
        hasFailingJobs: false,
        failedInSubprocess: false,
      }),
    ).toEqual(['dead-letter jobs'])
  })
})

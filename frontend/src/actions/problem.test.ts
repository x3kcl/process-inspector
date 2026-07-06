import { describe, expect, it } from 'vitest'
import { mayHaveExecuted, parseActionProblem, problemBanner } from './problem'

describe('parseActionProblem', () => {
  it('reads the machine-readable code, outcome and audit id from a ProblemDetail', () => {
    const problem = parseActionProblem(409, {
      title: 'Compare-and-set conflict — the edit was not applied',
      detail: 'orderTotal changed since it was read',
      code: 'cas-conflict',
      outcome: 'refused',
      auditId: 'a-1',
      currentValue: 42,
      expectedOldValue: 41,
    })
    expect(problem.code).toBe('cas-conflict')
    expect(problem.outcome).toBe('refused')
    expect(problem.auditId).toBe('a-1')
    expect(problem.currentValue).toBe(42)
    expect(problem.expectedOldValue).toBe(41)
  })

  it('keeps a null currentValue distinguishable (CAS against a cleared value)', () => {
    const problem = parseActionProblem(409, { code: 'cas-conflict', currentValue: null })
    expect(problem.currentValue).toBeNull()
  })

  it('maps a bare 403 (RBAC refusal without a problem body) to forbidden', () => {
    const problem = parseActionProblem(403, undefined)
    expect(problem.code).toBe('forbidden')
    expect(problem.outcome).toBe('refused')
  })

  it('defaults unknown outcome strings to refused — never silently to "it ran"', () => {
    const problem = parseActionProblem(500, { code: 'weird', outcome: 'partial' })
    expect(problem.outcome).toBe('refused')
  })
})

describe('mayHaveExecuted', () => {
  it('is true only for the dispatched-unverified legs', () => {
    expect(
      mayHaveExecuted(parseActionProblem(504, { code: 'outcome-unknown', outcome: 'unknown' })),
    ).toBe(true)
    expect(
      mayHaveExecuted(
        parseActionProblem(500, { code: 'outcome-verification-failed', outcome: 'unknown' }),
      ),
    ).toBe(true)
    expect(
      mayHaveExecuted(parseActionProblem(503, { code: 'audit-unavailable', outcome: 'refused' })),
    ).toBe(false)
    expect(
      mayHaveExecuted(parseActionProblem(409, { code: 'engine-rejected', outcome: 'failed' })),
    ).toBe(false)
  })
})

describe('problemBanner — the three-way SPEC §6 distinction stays visible', () => {
  it('fail-closed 503 says nothing happened and names the audit store', () => {
    const text = problemBanner(
      parseActionProblem(503, { code: 'audit-unavailable', outcome: 'refused' }),
    )
    expect(text).toContain('NOT sent')
    expect(text).toContain('audit store')
  })

  it('cas-conflict frames the refusal as protection, never as an error to force through', () => {
    const text = problemBanner(parseActionProblem(409, { code: 'cas-conflict' }))
    expect(text).toContain('nothing was overwritten')
  })

  it('engine rejection quotes the engine', () => {
    const text = problemBanner(
      parseActionProblem(409, {
        code: 'engine-rejected',
        outcome: 'failed',
        engineBody: 'job 7 not found',
      }),
    )
    expect(text).toContain('nothing happened')
    expect(text).toContain('job 7 not found')
  })

  it('outcome-unknown warns against resubmitting', () => {
    const text = problemBanner(
      parseActionProblem(504, { code: 'outcome-unknown', outcome: 'unknown' }),
    )
    expect(text).toContain('Do not resubmit')
  })

  it('guard refusals pass the BFF sentence through verbatim', () => {
    const text = problemBanner(
      parseActionProblem(400, {
        code: 'reason-too-short',
        detail: 'The reason must be at least 10 characters.',
      }),
    )
    expect(text).toBe('The reason must be at least 10 characters.')
  })
})

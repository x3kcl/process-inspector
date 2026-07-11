import { describe, expect, it } from 'vitest'
import { isReauthChallenge, mayHaveExecuted, parseActionProblem, problemBanner } from './problem'

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

  it('never invents an RBAC verdict for a code-less 403 (usability W1#1, Theme T1)', () => {
    const problem = parseActionProblem(403, undefined)
    expect(problem.code).toBe('unexpected')
    expect(problem.outcome).toBe('refused')
  })

  it('recognises the bare Spring error shape — the request never reached the BFF handlers', () => {
    const spring = parseActionProblem(403, {
      timestamp: '2026-07-10T12:00:00Z',
      status: 403,
      error: 'Forbidden',
      path: '/api/engines/engine-a/actions/retry',
    })
    expect(spring.bareSpringError).toBe(true)
    // A ProblemDetail with a machine-readable code is NOT the bare shape.
    const problemDetail = parseActionProblem(403, { code: 'rbac-denied', status: 403 })
    expect(problemDetail.bareSpringError).toBe(false)
    expect(parseActionProblem(403, undefined).bareSpringError).toBe(false)
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

  it('falls back to the plain refusal sentence when the BFF sent no detail (Theme F)', () => {
    const text = problemBanner(parseActionProblem(400, { code: 'weird' }))
    expect(text).toBe(
      'The request was refused before anything ran — nothing happened. (Technical detail: HTTP 400.)',
    )
  })

  it('engine-read-only reads as OWNER POLICY, never byte-identical to an RBAC denial (W1#4, theme T6)', () => {
    const readOnly = problemBanner(
      parseActionProblem(403, {
        code: 'engine-read-only',
        outcome: 'refused',
        detail: "Engine 'engine-7' is registered read-only (R-GOV-04) — 'retry' is rejected.",
      }),
    )
    expect(readOnly).toContain('read-only')
    expect(readOnly).toContain('engine owner')
    expect(readOnly).toContain('not your role')
    expect(readOnly).toContain("Engine 'engine-7'") // the BFF sentence stays quoted
    // Distinct from RBAC copy — the same status code must never render the role verdict.
    expect(readOnly).not.toContain('Your role does not permit')
    const rbac = problemBanner(parseActionProblem(403, { code: 'rbac-denied' }))
    expect(readOnly).not.toBe(rbac)
  })

  it('renders the RBAC copy ONLY for the rbac-denied code, quoting the missing grant', () => {
    const text = problemBanner(
      parseActionProblem(403, {
        code: 'rbac-denied',
        detail: "'retry' (tier 1) requires OPERATOR on engine 'engine-a'.",
      }),
    )
    expect(text).toContain('Your role does not permit this action')
    expect(text).toContain("requires OPERATOR on engine 'engine-a'")
  })

  it('a code-less 403 gets the honest default, never the RBAC copy (usability W1#1)', () => {
    const text = problemBanner(parseActionProblem(403, undefined))
    expect(text).toBe('The server refused this request before anything ran — HTTP 403.')
    expect(text).not.toContain('Your role')
  })

  it('a bare-Spring-shaped 403 adds the missing-CSRF hint with the sign-out remedy', () => {
    const text = problemBanner(
      parseActionProblem(403, {
        timestamp: '2026-07-10T12:00:00Z',
        status: 403,
        error: 'Forbidden',
        path: '/api/engines/engine-a/actions/retry',
      }),
    )
    expect(text).toContain('The server refused this request before anything ran — HTTP 403.')
    expect(text).toContain('CSRF token')
    expect(text).toContain('sign out and back in')
    expect(text).not.toContain('Your role')
  })
})

describe('requestId — the quotable support id (usability W1#6, R-AUD-04)', () => {
  it('parses the additive requestId property off any ProblemDetail', () => {
    const problem = parseActionProblem(403, { code: 'rbac-denied', requestId: 'req-4711' })
    expect(problem.requestId).toBe('req-4711')
    expect(parseActionProblem(403, { code: 'rbac-denied' }).requestId).toBeUndefined()
  })

  it('parses the requestId off the bare Spring error shape too', () => {
    const problem = parseActionProblem(403, {
      timestamp: '2026-07-10T12:00:00Z',
      status: 403,
      error: 'Forbidden',
      path: '/api/search',
      requestId: 'req-bare-1',
    })
    expect(problem.bareSpringError).toBe(true)
    expect(problem.requestId).toBe('req-bare-1')
  })

  it('every banner ends with the quote-this-ID next move when the id is present', () => {
    const withId = problemBanner(
      parseActionProblem(403, { code: 'rbac-denied', requestId: 'req-4711' }),
    )
    expect(withId).toContain('Quote request ID req-4711 to support')
    // the guard-sentence passthrough leg too
    const guard = problemBanner(
      parseActionProblem(400, {
        code: 'reason-too-short',
        detail: 'The reason must be at least 10 characters.',
        requestId: 'req-4712',
      }),
    )
    expect(guard).toContain('The reason must be at least 10 characters.')
    expect(guard).toContain('Quote request ID req-4712 to support')
    // and the bare-shape fallback leg
    const bare = problemBanner(
      parseActionProblem(403, { status: 403, error: 'Forbidden', requestId: 'req-4713' }),
    )
    expect(bare).toContain('Quote request ID req-4713 to support')
  })

  it('adds no id line when the server sent none — never invents an id', () => {
    const text = problemBanner(parseActionProblem(403, { code: 'rbac-denied' }))
    expect(text).not.toContain('Quote request ID')
  })
})

describe('reauth-required — the dangerous-set freshness challenge (IDP-SECURITY.md §5)', () => {
  it('is recognised as a challenge, not a plain failure', () => {
    const problem = parseActionProblem(401, {
      code: 'reauth-required',
      outcome: 'refused',
      detail: 'Re-authentication required: …',
    })
    expect(isReauthChallenge(problem)).toBe(true)
    expect(problem.outcome).toBe('refused')
    expect(mayHaveExecuted(problem)).toBe(false)
  })

  it('carries re-auth copy in the banner fallback path', () => {
    const text = problemBanner(parseActionProblem(401, { code: 'reauth-required' }))
    expect(text).toContain('re-authenticate')
    expect(text).toContain('Nothing happened')
  })

  it('an ordinary 401 without the code is NOT a challenge', () => {
    expect(isReauthChallenge(parseActionProblem(401, {}))).toBe(false)
  })
})

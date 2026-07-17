import { describe, expect, it } from 'vitest'
import type { TeamViewDto } from '../api/model'
import { isDangling, scopeLabel, teamViewTitle } from './teamViewModel'
import { publishError } from './PublishToTeamButton'
import { ApiError } from '../api/client'

function view(over: Partial<TeamViewDto>): TeamViewDto {
  return {
    id: 1,
    name: 'Stuck payments',
    search: 'status=FAILED',
    scopeEngineId: '*',
    scopeTenantId: '*',
    author: 'alice',
    description: undefined,
    runbookUrl: undefined,
    danglingReason: undefined,
    createdAt: '2026-07-09T09:00:00Z',
    updatedAt: '2026-07-09T09:00:00Z',
    ...over,
  }
}

describe('teamViewModel', () => {
  it('detects dangling canon only when a reason is present', () => {
    expect(isDangling(view({}))).toBe(false)
    expect(isDangling(view({ danglingReason: '' }))).toBe(false)
    expect(isDangling(view({ danglingReason: 'the engine is gone' }))).toBe(true)
  })

  it('a wire-null danglingReason is NOT dangling — the W2 #4 "⚠ null (scope unavailable)" bug', () => {
    // Jackson serializes the resolvable case as danglingReason: null; the generated type
    // omits null, so a `!== undefined` check wrongly greyed every WILDCARD team view and
    // rendered "⚠ null" in its tooltip. Wildcard canon must stay a live, labeled link.
    const wildcard = view({ danglingReason: null as unknown as string })
    expect(isDangling(wildcard)).toBe(false)
    expect(teamViewTitle(wildcard)).not.toContain('null')
    expect(teamViewTitle(wildcard)).toContain('all engines')
  })

  it('renders a words-only scope label (never color-only)', () => {
    expect(scopeLabel(view({ scopeEngineId: '*', scopeTenantId: '*' }))).toBe('all engines')
    expect(scopeLabel(view({ scopeEngineId: 'orders-prod', scopeTenantId: '*' }))).toBe(
      'engine orders-prod',
    )
    expect(scopeLabel(view({ scopeEngineId: '*', scopeTenantId: 'tenant-a' }))).toBe(
      'tenant tenant-a',
    )
    expect(scopeLabel(view({ scopeEngineId: 'orders-prod', scopeTenantId: 'tenant-a' }))).toBe(
      'engine orders-prod · tenant tenant-a',
    )
  })

  it('title carries author, scope, and the dangling warning when present', () => {
    const t = teamViewTitle(
      view({ author: 'bob', description: 'the runbook', danglingReason: 'gone' }),
    )
    expect(t).toContain('by bob')
    expect(t).toContain('the runbook')
    expect(t).toContain('⚠')
    expect(t).toContain('gone')
  })

  it('maps governed refusals to [what]+[why] copy, never a bare status', () => {
    expect(publishError(new ApiError(403, {}))).toContain('permission')
    expect(publishError(new ApiError(409, {}))).toContain('already exists')
    expect(publishError(new ApiError(400, {}))).toContain('can’t be published')
    expect(publishError(new ApiError(503, {}))).toContain('Nothing was published')
    expect(publishError(new Error('network'))).toContain('Couldn’t publish')
  })

  it('surfaces the BFF’s own scope-aware 403 reason, not a hardcoded wildcard sentence (#234)', () => {
    // The backend's canPublish() refusal already names the real rule; the UI must quote it.
    const detail =
      'publishing this scope needs an OPERATOR (or ADMIN for a wildcard scope) grant on it'
    expect(publishError(new ApiError(403, { detail }))).toContain(detail)
    // The quotable request id rides along when the BFF stamped one (W1#6 contract).
    expect(publishError(new ApiError(403, { detail, requestId: 'req-42' }))).toContain('req-42')
    // Any server-stated reason is rendered verbatim — no client-side wildcard/ADMIN invention.
    const other = 'your grant does not cover engine orders-prod'
    expect(publishError(new ApiError(403, { detail: other }))).toContain(other)
    expect(publishError(new ApiError(403, { detail: other }))).not.toContain('wildcard')
  })

  it('falls back to generic [what] copy when the 403 body is missing or malformed (#234)', () => {
    for (const body of [{}, null, 'nope', { detail: 7 }, { title: 'Forbidden' }]) {
      const message = publishError(new ApiError(403, body))
      expect(message).toContain('permission')
      // Never claim a wildcard/ADMIN reason we cannot know.
      expect(message).not.toContain('wildcard')
    }
  })
})

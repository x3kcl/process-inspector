import { describe, expect, it } from 'vitest'
import { decodeSearch } from '../search/urlState'
import { LEAK_VIEWS, leakViewById } from './leakViews'

const decode = (search: string) => {
  const request = decodeSearch(new URLSearchParams(search))
  if (request === null) throw new Error('leak view produced an empty search')
  return request
}

describe('curated leak views (R-BAU-02) — honest per R-SEM-05', () => {
  it('ships exactly the three SPEC §4 windows', () => {
    expect(LEAK_VIEWS.map((view) => view.id)).toEqual([
      'activeOver30d',
      'activeOver90d',
      'suspendedStartedOver7d',
    ])
  })

  it('every leak view decodes through the Stage-1 URL codec — a view IS a replayable search', () => {
    for (const view of LEAK_VIEWS) {
      expect(
        decodeSearch(
          new URLSearchParams(view.search('vacationRequest', '2026-07-01T00:00:00.000Z')),
        ),
      ).not.toBeNull()
    }
  })

  it('the suspended leak view resolves to startedBefore — Flowable has no suspension timestamp to lie with', () => {
    const view = leakViewById('suspendedStartedOver7d')
    const request = decode(view.search('vacationRequest', '2026-07-04T00:00:00.000Z'))
    expect(request.statuses).toEqual(['SUSPENDED'])
    expect(request.processDefinitionKey).toBe('vacationRequest')
    // Age is measured off startTime, exactly as the label promises.
    expect(request.startedBefore).toBe('2026-07-04T00:00:00.000Z')
    // No fabricated suspension-time predicate anywhere in the params.
    expect(request.startedAfter).toBeUndefined()
    expect(request.failureTimeAfter).toBeUndefined()
    expect(request.failureTimeBefore).toBeUndefined()
    // The label states the honest predicate and never implies time-since-suspension.
    expect(view.label).toContain('started')
    expect(view.label).not.toMatch(/suspended (for|>|more than) *\d/i)
    expect(view.note).toContain('no suspension timestamp')
  })

  it.each([['activeOver30d'] as const, ['activeOver90d'] as const])(
    'the %s active leak view is ACTIVE + startedBefore, scoped to one definition',
    (id) => {
      const view = leakViewById(id)
      const request = decode(view.search('loanApproval', '2026-06-11T00:00:00.000Z'))
      expect(request.statuses).toEqual(['ACTIVE'])
      expect(request.processDefinitionKey).toBe('loanApproval')
      expect(request.startedBefore).toBe('2026-06-11T00:00:00.000Z')
      expect(request.startedAfter).toBeUndefined()
      expect(view.label).toContain('started')
    },
  )
})

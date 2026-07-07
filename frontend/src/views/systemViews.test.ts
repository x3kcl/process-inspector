import { describe, expect, it } from 'vitest'
import { decodeSearch } from '../search/urlState'
import { sameSearch } from './model'
import { SYSTEM_VIEWS, minuteFloor } from './systemViews'

const NOW = new Date('2026-07-07T14:32:41.512Z')

const byId = (id: string) => {
  const found = SYSTEM_VIEWS.find((view) => view.id === id)
  if (found === undefined) throw new Error(`missing system view ${id}`)
  return found
}

const decode = (search: string) => {
  const request = decodeSearch(new URLSearchParams(search))
  if (request === null) throw new Error('system view produced an empty search')
  return request
}

describe('curated system views (SPEC §4, honesty rule R-SEM-05)', () => {
  it('ships exactly the four SPEC §4 views', () => {
    expect(SYSTEM_VIEWS.map((view) => view.id)).toEqual([
      'sys-failed-all',
      'sys-failed-1h',
      'sys-suspended-24h',
      'sys-started-1h',
    ])
  })

  it('every view decodes through the Stage 1 URL codec — a view IS a replayable search', () => {
    for (const view of SYSTEM_VIEWS) {
      expect(decodeSearch(new URLSearchParams(view.search(NOW)))).not.toBeNull()
    }
  })

  it('Failed (all engines): FAILED across every engine, failure-time sorted, no time window', () => {
    const request = decode(byId('sys-failed-all').search(NOW))
    expect(request.statuses).toEqual(['FAILED'])
    expect(request.engineIds).toBeUndefined()
    expect(request.sortBy).toBe('failureTime')
    expect(request.failureTimeAfter).toBeUndefined()
    expect(request.startedAfter).toBeUndefined()
  })

  it('Failed in the last hour rides failure time (DLQ createTime), never instance start', () => {
    const request = decode(byId('sys-failed-1h').search(NOW))
    expect(request.statuses).toEqual(['FAILED'])
    expect(request.failureTimeAfter).toBe('2026-07-07T13:32:00.000Z')
    expect(request.startedAfter).toBeUndefined()
    expect(request.startedBefore).toBeUndefined()
  })

  it('Suspended > 24h uses startedBefore — Flowable has no suspension timestamp to lie with', () => {
    const view = byId('sys-suspended-24h')
    const request = decode(view.search(NOW))
    expect(request.statuses).toEqual(['SUSPENDED'])
    expect(request.startedBefore).toBe('2026-07-06T14:32:00.000Z')
    // No fabricated suspension-time predicate anywhere in the params.
    expect(request.failureTimeBefore).toBeUndefined()
    expect(request.failureTimeAfter).toBeUndefined()
    // The honest scoping is declared to the operator, not hidden.
    expect(view.name).toContain('by start time')
    expect(view.note).toContain('no suspension timestamp')
  })

  it('Started in the last hour is status-agnostic', () => {
    const request = decode(byId('sys-started-1h').search(NOW))
    expect(request.statuses).toBeUndefined()
    expect(request.startedAfter).toBe('2026-07-07T13:32:00.000Z')
  })

  it('minute-floors "now": the URL is stable (highlight-matchable) within the minute', () => {
    expect(minuteFloor(NOW).toISOString()).toBe('2026-07-07T14:32:00.000Z')
    const later = new Date('2026-07-07T14:32:59.999Z')
    const nextMinute = new Date('2026-07-07T14:33:00.000Z')
    for (const view of SYSTEM_VIEWS) {
      expect(sameSearch(view.search(NOW), view.search(later))).toBe(true)
    }
    const relative = byId('sys-failed-1h')
    expect(sameSearch(relative.search(NOW), relative.search(nextMinute))).toBe(false)
  })
})

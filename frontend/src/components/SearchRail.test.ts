// #118 item 3: the date/time filter split (two single-segment inputs, not one
// datetime-local — see SearchRail.tsx's DateTimeFilterField doc comment) needs its
// split/join round-trip proven, since a wrong combine would silently corrupt the search
// filter's time bounds.
import { describe, expect, it } from 'vitest'
import { localToIso, splitLocal } from './SearchRail'

describe('splitLocal', () => {
  it('splits an ISO instant into separate date and time strings', () => {
    const { date, time } = splitLocal('2026-07-09T14:30:00.000Z')
    expect(date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(time).toMatch(/^\d{2}:\d{2}$/)
  })

  it('returns empty strings for undefined (unset filter)', () => {
    expect(splitLocal(undefined)).toEqual({ date: '', time: '' })
  })

  it('returns empty strings for an unparseable value, never throws', () => {
    expect(splitLocal('not a date')).toEqual({ date: '', time: '' })
  })
})

describe('localToIso', () => {
  it('round-trips through splitLocal back to an equivalent instant', () => {
    const original = '2026-07-09T14:30:00.000Z'
    const roundTripped = localToIso(splitLocal(original))
    expect(new Date(roundTripped ?? '').getTime()).toBe(new Date(original).getTime())
  })

  it('is undefined when the date half is empty — an unset filter, not midnight', () => {
    expect(localToIso({ date: '', time: '09:00' })).toBeUndefined()
  })

  it('defaults the time to midnight when only a date was entered', () => {
    const iso = localToIso({ date: '2026-07-09', time: '' })
    expect(iso).toBeDefined()
    const parsed = new Date(iso ?? '')
    expect(parsed.getHours()).toBe(0)
    expect(parsed.getMinutes()).toBe(0)
  })

  it('is undefined for a malformed date/time combination', () => {
    expect(localToIso({ date: 'not-a-date', time: '09:00' })).toBeUndefined()
  })
})

// R-UXQ-03 time-display honesty (usability W1#3): the one shared formatter carries an
// explicit zone token, a UTC ISO normalizer for hover/aria, a relative age, and a
// persisted one-click display-zone store (SPEC §10a).
import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatRelative, formatSeconds, toUtcIso } from './format'

function stubStorage(initial: Record<string, string> = {}): Map<string, string> {
  const store = new Map(Object.entries(initial))
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, value)
    },
  })
  return store
}

/** Fresh module instance so each test exercises the boot-time localStorage read. */
async function freshFormat(): Promise<typeof import('./format')> {
  vi.resetModules()
  return import('./format')
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('display zone store (one-click UTC, SPEC §10a)', () => {
  it('defaults to browser-local', async () => {
    stubStorage()
    const f = await freshFormat()
    expect(f.getDisplayZone()).toBe('local')
  })

  it('one click to UTC — the shared formatter follows immediately', async () => {
    stubStorage()
    const f = await freshFormat()
    f.setDisplayZone('utc')
    const text = f.formatDateTime('2026-07-10T14:00:00+02:00')
    expect(text).toContain('UTC')
    // 14:00+02:00 is 12:00 UTC — the wall-clock must be the UTC one.
    expect(text).toMatch(/12/)
  })

  it('persists the choice and reads it back on the next boot', async () => {
    const store = stubStorage()
    const first = await freshFormat()
    first.setDisplayZone('utc')
    expect(store.get('inspector.displayZone')).toBe('utc')
    const second = await freshFormat() // fresh module = fresh browser session
    expect(second.getDisplayZone()).toBe('utc')
  })

  it('falls back to local on a corrupt stored value', async () => {
    stubStorage({ 'inspector.displayZone': 'garbage' })
    const f = await freshFormat()
    expect(f.getDisplayZone()).toBe('local')
  })

  it('survives an unavailable localStorage (private mode) — in-memory toggle still works', async () => {
    // No stub: the node test environment has no localStorage at all.
    const f = await freshFormat()
    expect(f.getDisplayZone()).toBe('local')
    expect(() => {
      f.setDisplayZone('utc')
    }).not.toThrow()
    expect(f.getDisplayZone()).toBe('utc')
  })

  it('notifies subscribers on toggle and stops after unsubscribe', async () => {
    stubStorage()
    const f = await freshFormat()
    const listener = vi.fn()
    const unsubscribe = f.subscribeDisplayZone(listener)
    f.setDisplayZone('utc')
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
    f.setDisplayZone('local')
    expect(listener).toHaveBeenCalledTimes(1)
  })
})

describe('formatDateTime', () => {
  it('carries an explicit zone token (timeZoneName short) in local mode', async () => {
    stubStorage()
    const f = await freshFormat()
    const zoneToken = new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
      .formatToParts(new Date('2026-07-10T14:00:00Z'))
      .find((part) => part.type === 'timeZoneName')?.value
    expect(zoneToken).toBeTruthy()
    expect(f.formatDateTime('2026-07-10T14:00:00Z')).toContain(zoneToken ?? '')
  })

  it('keeps rendering unparseable input verbatim and blanks empties', async () => {
    stubStorage()
    const f = await freshFormat()
    expect(f.formatDateTime('not-a-date')).toBe('not-a-date')
    expect(f.formatDateTime(null)).toBe('')
    expect(f.formatDateTime(undefined)).toBe('')
    expect(f.formatDateTime('')).toBe('')
  })
})

describe('formatClock', () => {
  it('carries the zone token and honors the UTC toggle', async () => {
    stubStorage()
    const f = await freshFormat()
    f.setDisplayZone('utc')
    const text = f.formatClock(Date.parse('2026-07-10T12:34:56Z'))
    expect(text).toContain('UTC')
    expect(text).toContain('34')
    expect(text).toContain('56')
  })
})

describe('toUtcIso (the hover/aria + machine-facing form)', () => {
  it('normalizes any parseable stamp to the UTC Z-form', () => {
    expect(toUtcIso('2026-07-10T14:00:00+02:00')).toBe('2026-07-10T12:00:00.000Z')
    expect(toUtcIso('2026-07-10T12:00:00.000Z')).toBe('2026-07-10T12:00:00.000Z')
  })

  it('passes through unparseable input and blanks empties', () => {
    expect(toUtcIso('not-a-date')).toBe('not-a-date')
    expect(toUtcIso(null)).toBe('')
    expect(toUtcIso(undefined)).toBe('')
    expect(toUtcIso('')).toBe('')
  })
})

describe('formatRelative', () => {
  const now = Date.parse('2026-07-10T12:00:00Z')

  it('renders a past stamp as "N ago" in the compact duration vocabulary', () => {
    expect(formatRelative('2026-07-10T11:59:18Z', now)).toBe('42s ago')
    expect(formatRelative('2026-07-10T08:40:00Z', now)).toBe('3h 20m ago')
    expect(formatRelative('2026-07-05T12:00:00Z', now)).toBe('5d ago')
  })

  it('renders a future stamp as "in N" (timer due dates, next retry)', () => {
    expect(formatRelative('2026-07-10T12:20:00Z', now)).toBe('in 20m')
  })

  it('reads "just now" inside the immediate window', () => {
    expect(formatRelative('2026-07-10T11:59:58Z', now)).toBe('just now')
    expect(formatRelative('2026-07-10T12:00:02Z', now)).toBe('just now')
  })

  it('blanks invalid or missing input', () => {
    expect(formatRelative('not-a-date', now)).toBe('')
    expect(formatRelative(null, now)).toBe('')
    expect(formatRelative(undefined, now)).toBe('')
    expect(formatRelative('', now)).toBe('')
  })
})

describe('formatSeconds (unchanged compact durations)', () => {
  it('keeps the existing vocabulary', () => {
    expect(formatSeconds(42)).toBe('42s')
    expect(formatSeconds(12 * 60)).toBe('12m')
    expect(formatSeconds(3 * 3600 + 20 * 60)).toBe('3h 20m')
    expect(formatSeconds(5 * 86400)).toBe('5d')
  })
})

// The one shared formatter (R-UXQ-07): every date/number/duration the UI shows goes
// through here. Machine-facing text (cURL, copies) stays raw UTC ISO-8601 — never local.
//
// R-UXQ-03 time-display honesty (SPEC §10a): every human timestamp carries an explicit
// zone token (timeZoneName short), and the whole app renders in a user-selected display
// zone — browser-local by default, one click to UTC, persisted per browser. The <Ts>
// element in ./Ts.tsx adds the hover/aria UTC ISO and the relative age on top.
import { useSyncExternalStore } from 'react'

export type DisplayZone = 'local' | 'utc'

const ZONE_STORAGE_KEY = 'inspector.displayZone'

// Lazy so the localStorage read happens on first use, not import (testable, SSR-safe).
let zone: DisplayZone | null = null
const zoneListeners = new Set<() => void>()

function storedZone(): DisplayZone {
  try {
    return globalThis.localStorage.getItem(ZONE_STORAGE_KEY) === 'utc' ? 'utc' : 'local'
  } catch {
    // No storage (private mode / node) — the honest default is the browser's own zone.
    return 'local'
  }
}

export function getDisplayZone(): DisplayZone {
  zone ??= storedZone()
  return zone
}

export function setDisplayZone(next: DisplayZone): void {
  zone = next
  try {
    globalThis.localStorage.setItem(ZONE_STORAGE_KEY, next)
  } catch {
    // Persistence unavailable — the in-memory choice still applies for this session.
  }
  for (const listener of zoneListeners) listener()
}

export function subscribeDisplayZone(listener: () => void): () => void {
  zoneListeners.add(listener)
  return () => {
    zoneListeners.delete(listener)
  }
}

/** React binding: re-renders the caller whenever the display zone toggles. */
export function useDisplayZone(): DisplayZone {
  return useSyncExternalStore(subscribeDisplayZone, getDisplayZone, getDisplayZone)
}

// dateStyle/timeStyle cannot be combined with timeZoneName, so the fields are explicit.
const DATE_TIME_OPTIONS: Intl.DateTimeFormatOptions = {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  timeZoneName: 'short',
}
const CLOCK_OPTIONS: Intl.DateTimeFormatOptions = {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  timeZoneName: 'short',
}

const formatterCache = new Map<string, Intl.DateTimeFormat>()

function formatterFor(kind: 'dateTime' | 'clock'): Intl.DateTimeFormat {
  const displayZone = getDisplayZone()
  const cacheKey = `${kind}:${displayZone}`
  let formatter = formatterCache.get(cacheKey)
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat(undefined, {
      ...(kind === 'dateTime' ? DATE_TIME_OPTIONS : CLOCK_OPTIONS),
      timeZone: displayZone === 'utc' ? 'UTC' : undefined,
    })
    formatterCache.set(cacheKey, formatter)
  }
  return formatter
}

const count = new Intl.NumberFormat()

/**
 * Absolute rendering of an engine ISO timestamp in the selected display zone, always
 * with an explicit zone token; the full UTC ISO belongs in a tooltip (use <Ts>).
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (iso == null || iso === '') return ''
  const parsed = new Date(iso)
  return Number.isNaN(parsed.getTime()) ? iso : formatterFor('dateTime').format(parsed)
}

/** HH:mm:ss + zone token for the snapshot "as of" header, in the selected display zone. */
export function formatClock(epochMillis: number): string {
  return formatterFor('clock').format(new Date(epochMillis))
}

/** The normalized UTC ISO-8601 form for hover/aria and machine-facing text. */
export function toUtcIso(iso: string | null | undefined): string {
  if (iso == null || iso === '') return ''
  const parsed = new Date(iso)
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toISOString()
}

/** Relative age — "42s ago" / "3h 20m ago" for the past, "in 20m" for due dates. */
export function formatRelative(iso: string | null | undefined, nowMs = Date.now()): string {
  if (iso == null || iso === '') return ''
  const stamp = new Date(iso).getTime()
  if (Number.isNaN(stamp)) return ''
  const diffSeconds = Math.round((nowMs - stamp) / 1000)
  if (Math.abs(diffSeconds) < 5) return 'just now'
  return diffSeconds > 0 ? `${formatSeconds(diffSeconds)} ago` : `in ${formatSeconds(-diffSeconds)}`
}

export function formatCount(n: number): string {
  return count.format(n)
}

/** Compact duration for job ages: 42s, 12m, 3h 20m, 5d. */
export function formatSeconds(seconds: number): string {
  if (seconds < 60) return `${String(seconds)}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${String(minutes)}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${String(hours)}h ${String(minutes % 60)}m`
  return `${String(Math.floor(hours / 24))}d`
}

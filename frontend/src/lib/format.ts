// The one shared formatter (R-UXQ-07): every date/number/duration the UI shows goes
// through here. Machine-facing text (cURL, copies) stays raw UTC ISO-8601 — never local.

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' })
const clock = new Intl.DateTimeFormat(undefined, {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
})
const count = new Intl.NumberFormat()

/** Absolute local rendering of an engine ISO timestamp; the raw ISO belongs in a tooltip. */
export function formatDateTime(iso: string | null | undefined): string {
  if (iso == null || iso === '') return ''
  const parsed = new Date(iso)
  return Number.isNaN(parsed.getTime()) ? iso : dateTime.format(parsed)
}

/** HH:mm:ss for the snapshot "as of" header. */
export function formatClock(epochMillis: number): string {
  return clock.format(new Date(epochMillis))
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

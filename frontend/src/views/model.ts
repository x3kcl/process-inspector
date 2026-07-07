// v1.x saved views & recent searches (SPEC §4 Stage 0, §8): a view is NOTHING but a named
// URL search string — clicking one replays the exact Stage 1 URL state through the M2b
// codec (URL primacy). Persistence is localStorage for v1.x; every payload carries an
// explicit schema version so the v2 server-side store (SPEC §8) can parse and upgrade
// local data deterministically instead of forensically.
import type { SearchRequest } from '../api/model'

export const SCHEMA_VERSION = 1
export const RECENT_CAP = 10

export interface SavedView {
  id: string
  name: string
  /** Canonical URL search string WITHOUT the leading '?' — the ENTIRE view state. */
  search: string
  createdAt: string
}

export interface RecentSearch {
  /** Canonical URL search string WITHOUT the leading '?'. */
  search: string
  /** Human-readable criteria summary, generated once at record time. */
  label: string
  at: string
}

export function encodeEnvelope(items: unknown[]): string {
  return JSON.stringify({ version: SCHEMA_VERSION, items })
}

/** Unknown version, corrupt JSON or malformed items degrade to empty — never throw. */
export function decodeEnvelope<T>(raw: string | null, isItem: (value: unknown) => value is T): T[] {
  if (raw === null) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed === null || typeof parsed !== 'object') return []
    const envelope = parsed as { version?: unknown; items?: unknown }
    if (envelope.version !== SCHEMA_VERSION || !Array.isArray(envelope.items)) return []
    return envelope.items.filter(isItem)
  } catch {
    return []
  }
}

export function isSavedView(value: unknown): value is SavedView {
  if (value === null || typeof value !== 'object') return false
  const view = value as Partial<SavedView>
  return (
    typeof view.id === 'string' &&
    typeof view.name === 'string' &&
    typeof view.search === 'string' &&
    typeof view.createdAt === 'string'
  )
}

export function isRecentSearch(value: unknown): value is RecentSearch {
  if (value === null || typeof value !== 'object') return false
  const recent = value as Partial<RecentSearch>
  return (
    typeof recent.search === 'string' &&
    typeof recent.label === 'string' &&
    typeof recent.at === 'string'
  )
}

/** Key-sorted canonical form: "a=1&b=2" and "b=2&a=1" are the same search. */
export function normalizeSearch(search: string): string {
  const params = new URLSearchParams(search)
  params.sort()
  return params.toString()
}

export function sameSearch(a: string, b: string): boolean {
  return normalizeSearch(a) === normalizeSearch(b)
}

/** Most-recent-first, deduped on the canonical search string, capped at RECENT_CAP. */
export function pushRecent(list: RecentSearch[], entry: RecentSearch): RecentSearch[] {
  const rest = list.filter((recent) => !sameSearch(recent.search, entry.search))
  return [entry, ...rest].slice(0, RECENT_CAP)
}

/** Saving under an existing name replaces that view in place — never a silent duplicate. */
export function upsertView(list: SavedView[], view: SavedView): SavedView[] {
  const at = list.findIndex((existing) => existing.name === view.name)
  if (at === -1) return [...list, view]
  return list.map((existing, index) => (index === at ? view : existing))
}

export function removeView(list: SavedView[], id: string): SavedView[] {
  return list.filter((view) => view.id !== id)
}

/** Compact criteria one-liner for recents, e.g. `FAILED · billing-prod · orderId: 123`. */
export function describeSearch(request: SearchRequest): string {
  const parts: string[] = []
  if (request.statuses !== undefined && request.statuses.length > 0) {
    parts.push(request.statuses.join('+'))
  }
  if (request.engineIds !== undefined && request.engineIds.length > 0) {
    parts.push(request.engineIds.join(', '))
  }
  if (isSet(request.processDefinitionKey)) parts.push(request.processDefinitionKey)
  if (isSet(request.businessKey)) parts.push(request.businessKey)
  else if (isSet(request.businessKeyLike)) parts.push(`~${request.businessKeyLike}`)
  if (isSet(request.currentActivity)) parts.push(`@${request.currentActivity}`)
  if (isSet(request.errorText)) parts.push(`"${request.errorText}"`)
  for (const variable of request.variables ?? []) {
    if (isSet(variable.name)) {
      parts.push(
        `${variable.name}${operationSymbol(variable.operation)}${valueText(variable.value)}`,
      )
    }
  }
  if (isSet(request.startedAfter)) parts.push(`started ≥ ${minuteUtc(request.startedAfter)}`)
  if (isSet(request.startedBefore)) parts.push(`started < ${minuteUtc(request.startedBefore)}`)
  if (isSet(request.failureTimeAfter)) parts.push(`failed ≥ ${minuteUtc(request.failureTimeAfter)}`)
  if (isSet(request.failureTimeBefore))
    parts.push(`failed < ${minuteUtc(request.failureTimeBefore)}`)
  return parts.length > 0 ? parts.join(' · ') : 'all instances'
}

function isSet(value: string | undefined): value is string {
  return value !== undefined && value !== ''
}

function operationSymbol(operation: string | undefined): string {
  switch (operation) {
    case 'like':
      return ' ~ '
    case 'greaterThan':
      return ' > '
    case 'lessThan':
      return ' < '
    default:
      return ': '
  }
}

function valueText(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value)
}

/** ISO trimmed to the minute, kept UTC (labels are stored text, not live formatting). */
function minuteUtc(iso: string): string {
  return iso.length >= 16 ? `${iso.slice(0, 16).replace('T', ' ')}Z` : iso
}

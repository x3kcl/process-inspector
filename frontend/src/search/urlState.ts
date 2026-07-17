// SPEC §4 Stage 1: the ENTIRE search state is URL-encoded — a shared link replays the
// exact search. The URL is the single source of truth; the form is just an editor for it.
import type { SearchRequest, VariableFilter } from '../api/model'
import { isInstanceStatus } from '../api/model'

const LIST_SEP = ','

// Every query-param key the codec owns. hasSearch() keys off this list, so unrelated
// params (e.g. a future selected-row deep link) never trigger a search.
const KEYS = [
  'engines',
  'status',
  'definitionKey',
  'version',
  'businessKey',
  'businessKeyLike',
  'startedAfter',
  'startedBefore',
  'failedAfter',
  'failedBefore',
  'errorText',
  'signature',
  'activity',
  'sortBy',
  'pageSize',
  'vars',
] as const

export function hasSearch(params: URLSearchParams): boolean {
  return KEYS.some((key) => params.has(key))
}

// #197: saved/shared-view column-visibility capture, folded into the SAME URL-encoded
// search string every saved/shared view already carries — never a side-channel DB column
// (docs/SHARED-VIEWS.md §8). Deliberately NOT in KEYS: a URL carrying only `cols` and no
// real filter is not "a search" (hasSearch()/Stage-0-vs-Stage-1 routing must stay
// unaffected), and `cols` is not part of SearchRequest (the backend's wire type) — it never
// crosses into an actual /api/search call, only into what gets saved/displayed.
const COLS_KEY = 'cols'

/** Sorted for determinism — two saves of the same hidden set must produce the same string. */
export function encodeHiddenColumns(params: URLSearchParams, cols: ReadonlySet<string>): void {
  if (cols.size === 0) {
    params.delete(COLS_KEY)
    return
  }
  params.set(COLS_KEY, Array.from(cols).sort().join(LIST_SEP))
}

/** Null when the URL carries no layout suggestion at all (vs. an explicit empty set). */
export function decodeHiddenColumns(params: URLSearchParams): Set<string> | null {
  const raw = params.get(COLS_KEY)
  if (raw === null) return null
  return new Set(raw.split(LIST_SEP).filter((id) => id !== ''))
}

export function encodeSearch(request: SearchRequest): URLSearchParams {
  const params = new URLSearchParams()
  const set = (key: string, value: string | undefined) => {
    if (value !== undefined && value !== '') params.set(key, value)
  }
  const setList = (key: string, value: string[] | undefined) => {
    if (value !== undefined && value.length > 0) params.set(key, value.join(LIST_SEP))
  }
  setList('engines', request.engineIds)
  setList('status', request.statuses)
  set('definitionKey', request.processDefinitionKey)
  if (request.definitionVersion !== undefined) {
    params.set('version', String(request.definitionVersion))
  }
  set('businessKey', request.businessKey)
  set('businessKeyLike', request.businessKeyLike)
  set('startedAfter', request.startedAfter)
  set('startedBefore', request.startedBefore)
  set('failedAfter', request.failureTimeAfter)
  set('failedBefore', request.failureTimeBefore)
  set('errorText', request.errorText)
  set('signature', request.signatureHash)
  set('activity', request.currentActivity)
  set('sortBy', request.sortBy)
  if (request.pageSize !== undefined) params.set('pageSize', String(request.pageSize))
  if (request.variables !== undefined && request.variables.length > 0) {
    params.set('vars', JSON.stringify(request.variables))
  }
  return params
}

/** Null when the URL carries no search — the "no search yet" zero state. */
export function decodeSearch(params: URLSearchParams): SearchRequest | null {
  if (!hasSearch(params)) return null
  const get = (key: string) => params.get(key) ?? undefined
  const list = (key: string) =>
    params
      .get(key)
      ?.split(LIST_SEP)
      .filter((item) => item !== '')
  const pageSizeRaw = params.get('pageSize')
  const pageSize = pageSizeRaw === null ? undefined : Number.parseInt(pageSizeRaw, 10)
  const versionRaw = params.get('version')
  const version = versionRaw === null ? undefined : Number.parseInt(versionRaw, 10)
  return {
    engineIds: list('engines'),
    statuses: list('status')?.filter(isInstanceStatus),
    processDefinitionKey: get('definitionKey'),
    // #233: kept even without definitionKey — the BFF rejects that combination loudly
    // ("definitionVersion requires processDefinitionKey"); silently widening the scope
    // by dropping the filter is exactly the wrong-target bug this codec key fixes.
    definitionVersion: version !== undefined && Number.isFinite(version) ? version : undefined,
    businessKey: get('businessKey'),
    businessKeyLike: get('businessKeyLike'),
    startedAfter: get('startedAfter'),
    startedBefore: get('startedBefore'),
    failureTimeAfter: get('failedAfter'),
    failureTimeBefore: get('failedBefore'),
    errorText: get('errorText'),
    signatureHash: get('signature'),
    currentActivity: get('activity'),
    sortBy: get('sortBy'),
    pageSize: pageSize !== undefined && Number.isFinite(pageSize) ? pageSize : undefined,
    variables: decodeVariables(params.get('vars')),
  }
}

function decodeVariables(raw: string | null): VariableFilter[] | undefined {
  if (raw === null) return undefined
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return undefined
    const filters = parsed.filter(isVariableFilter)
    return filters.length > 0 ? filters : undefined
  } catch {
    return undefined
  }
}

function isVariableFilter(value: unknown): value is VariableFilter {
  return (
    value !== null && typeof value === 'object' && 'name' in value && typeof value.name === 'string'
  )
}

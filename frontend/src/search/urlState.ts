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
  'businessKey',
  'businessKeyLike',
  'startedAfter',
  'startedBefore',
  'failedAfter',
  'failedBefore',
  'errorText',
  'activity',
  'sortBy',
  'pageSize',
  'vars',
] as const

export function hasSearch(params: URLSearchParams): boolean {
  return KEYS.some((key) => params.has(key))
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
  set('businessKey', request.businessKey)
  set('businessKeyLike', request.businessKeyLike)
  set('startedAfter', request.startedAfter)
  set('startedBefore', request.startedBefore)
  set('failedAfter', request.failureTimeAfter)
  set('failedBefore', request.failureTimeBefore)
  set('errorText', request.errorText)
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
  return {
    engineIds: list('engines'),
    statuses: list('status')?.filter(isInstanceStatus),
    processDefinitionKey: get('definitionKey'),
    businessKey: get('businessKey'),
    businessKeyLike: get('businessKeyLike'),
    startedAfter: get('startedAfter'),
    startedBefore: get('startedBefore'),
    failureTimeAfter: get('failedAfter'),
    failureTimeBefore: get('failedBefore'),
    errorText: get('errorText'),
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

import { api, ApiError } from './client'
import type {
  AuditEntryDto,
  EngineDto,
  InstanceDetail,
  InstanceDiagram,
  InstanceHierarchy,
  InstanceJobs,
  InstanceTasks,
  InstanceTimeline,
  InstanceVariables,
  NearestSiblingResponse,
  NoteDto,
  ResolveResponse,
  SearchRequest,
  SearchResponse,
  SiblingDiffResponse,
  TriageDashboardResponse,
  VariableDto,
} from './model'

export async function fetchEngines(): Promise<EngineDto[]> {
  const { data, error, response } = await api.GET('/api/engines')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function runSearch(request: SearchRequest): Promise<SearchResponse> {
  const { data, error, response } = await api.POST('/api/search', { body: request })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Stage 0 aggregations; refresh=true bypasses the BFF's 20s cache (rate-limited). */
export async function fetchTriage(refresh: boolean): Promise<TriageDashboardResponse> {
  const { data, error, response } = await api.GET('/api/triage', {
    params: { query: refresh ? { refresh: true } : {} },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceAudit(
  engineId: string,
  instanceId: string,
): Promise<AuditEntryDto[]> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}/audit', {
    params: { path: { engineId, instanceId } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceNotes(engineId: string, instanceId: string): Promise<NoteDto[]> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}/notes', {
    params: { path: { engineId, instanceId } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/* ---------- Stage 2 detail reads (M3) — one fetcher per endpoint ---------- */

type InstancePath = { engineId: string; instanceId: string }

export async function fetchInstanceVitals(path: InstancePath): Promise<InstanceDetail> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}', {
    params: { path },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceDiagram(path: InstancePath): Promise<InstanceDiagram> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/diagram',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceVariables(path: InstancePath): Promise<InstanceVariables> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/variables',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** The escape hatch behind a >256 KiB truncated ledger row — explicit fetch only. */
export async function fetchInstanceVariable(
  path: InstancePath,
  name: string,
): Promise<VariableDto> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/variables/{name}',
    { params: { path: { ...path, name } } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceJobs(path: InstancePath): Promise<InstanceJobs> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}/jobs', {
    params: { path },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Plain-text stacktrace, fetched on expand (SPEC §4) — never eagerly per row. */
export async function fetchJobStacktrace(
  path: InstancePath,
  jobId: string,
  lane: string,
): Promise<string> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/jobs/{jobId}/stacktrace',
    { params: { path: { ...path, jobId }, query: { lane } }, parseAs: 'text' },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceTasks(path: InstancePath): Promise<InstanceTasks> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}/tasks', {
    params: { path },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceHierarchy(path: InstancePath): Promise<InstanceHierarchy> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/hierarchy',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceTimeline(path: InstancePath): Promise<InstanceTimeline> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/timeline',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/* ---------- Sibling diff (v1.x #5, SPEC §5.2) — read-only historic comparison ---------- */

/** The smart default: the most recently completed instance of the same definition version. */
export async function fetchNearestSibling(path: InstancePath): Promise<NearestSiblingResponse> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/nearest-sibling',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** The three-way diff of this instance (subject) against a chosen sibling. */
export async function fetchSiblingDiff(
  path: InstancePath,
  siblingId: string,
): Promise<SiblingDiffResponse> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/diff/{siblingId}',
    { params: { path: { ...path, siblingId } } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** The omnibox resolver (R-SEM-04): one pasted string across all reachable engines. */
export async function resolveQuery(q: string): Promise<ResolveResponse> {
  const { data, error, response } = await api.GET('/api/resolve', { params: { query: { q } } })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function createInstanceNote(
  engineId: string,
  instanceId: string,
  body: string,
): Promise<NoteDto> {
  const { data, error, response } = await api.POST('/api/instances/{engineId}/{instanceId}/notes', {
    params: { path: { engineId, instanceId } },
    body: { body },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

import { api, ApiError } from './client'
import type {
  AuditEntryDto,
  EngineDto,
  NoteDto,
  SearchRequest,
  SearchResponse,
  TriageDashboardResponse,
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

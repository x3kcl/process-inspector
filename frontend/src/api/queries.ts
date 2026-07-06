import { api, ApiError } from './client'
import type { EngineDto, SearchRequest, SearchResponse } from './model'

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

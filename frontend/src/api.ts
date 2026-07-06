import type { EngineInfo, SearchRequest, SearchResponse } from './types'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}: ${await res.text()}`)
  }
  return res.json() as Promise<T>
}

export function fetchEngines(): Promise<EngineInfo[]> {
  return fetch('/api/engines').then((r) => json<EngineInfo[]>(r))
}

export function search(req: SearchRequest): Promise<SearchResponse> {
  return fetch('/api/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }).then((r) => json<SearchResponse>(r))
}

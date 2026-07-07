// The M5 bulk surface (SPEC §7). NO OPTIMISTIC STATE anywhere in this module: submit
// inserts nothing client-side, progress is polled re-fetch of the PERSISTED job rows,
// and per-item outcomes render exactly what the BFF returns. Mutations never retry.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ActionError } from './actions'
import { api } from './client'
import { parseActionProblem } from '../actions/problem'
import type { components } from './schema'

export type BulkJobDto = components['schemas']['BulkJobDto']
export type BulkItemDto = components['schemas']['BulkItemDto']
export type BulkSubmitRequest = components['schemas']['BulkSubmitRequest']
export type BulkTarget = components['schemas']['BulkTarget']
export type BulkErrorClassRequest = components['schemas']['BulkErrorClassRequest']
export type BulkFilterRequest = components['schemas']['BulkFilterRequest']

export const BULK_JOBS_KEY = ['bulk-jobs']

export async function fetchBulkJobs(): Promise<BulkJobDto[]> {
  const { data, error, response } = await api.GET('/api/bulk')
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function fetchBulkJob(id: string): Promise<BulkJobDto> {
  const { data, error, response } = await api.GET('/api/bulk/{id}', { params: { path: { id } } })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function submitBulk(body: BulkSubmitRequest): Promise<BulkJobDto> {
  const { data, error, response } = await api.POST('/api/bulk', { body })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

/**
 * v1.x #1 — the triage group retry. SERVER-SIDE RESOLUTION is the contract: this sends
 * the error-class COORDINATES only; the BFF re-resolves the FAILED members from its own
 * capped signature scan and pipes them into the persisted bulk machinery. No instance ID
 * ever crosses the wire from the browser.
 */
export async function submitBulkErrorClass(body: BulkErrorClassRequest): Promise<BulkJobDto> {
  const { data, error, response } = await api.POST('/api/bulk/error-class', { body })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

/**
 * v1.x #2 — select-all-matching-filter. SERVER-SIDE RE-RESOLUTION is the contract: this
 * sends the SearchRequest criteria the grid is looking at; the BFF re-runs the search
 * plan exhaustively at execution time and records the resolved members in the audit
 * envelope before anything dispatches. No instance ID crosses the wire from the browser.
 */
export async function submitBulkFilter(body: BulkFilterRequest): Promise<BulkJobDto> {
  const { data, error, response } = await api.POST('/api/bulk/filter', { body })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function cancelBulk(id: string): Promise<BulkJobDto> {
  const { data, error, response } = await api.POST('/api/bulk/{id}/cancel', {
    params: { path: { id } },
  })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function verifyBulkItem(id: string, ordinal: number): Promise<BulkItemDto> {
  const { data, error, response } = await api.POST('/api/bulk/{id}/items/{ordinal}/verify', {
    params: { path: { id, ordinal } },
  })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export function jobActive(job: BulkJobDto): boolean {
  return job.state === 'PENDING' || job.state === 'RUNNING'
}

/**
 * The drawer's hydration read: state is server-side (survives navigation AND browser
 * refresh). With the SSE stream open ({@code live}) polling is a slow 30s safety net —
 * the stream's id-only events drive invalidation; offline it falls back to the v1
 * tightening poll.
 */
export function useBulkJobs(enabled: boolean, live = false) {
  return useQuery({
    queryKey: BULK_JOBS_KEY,
    queryFn: fetchBulkJobs,
    enabled,
    refetchInterval: (query) =>
      live ? 30_000 : (query.state.data ?? []).some(jobActive) ? 2500 : 30_000,
  })
}

export function useBulkJob(id: string | undefined, live = false) {
  return useQuery({
    queryKey: ['bulk-job', id],
    queryFn: () => fetchBulkJob(id ?? ''),
    enabled: id !== undefined,
    refetchInterval: (query) =>
      live ? false : query.state.data !== undefined && jobActive(query.state.data) ? 1500 : false,
  })
}

export function useSubmitBulk() {
  const queryClient = useQueryClient()
  return useMutation<BulkJobDto, ActionError, BulkSubmitRequest>({
    retry: false,
    mutationFn: submitBulk,
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: BULK_JOBS_KEY })
    },
  })
}

export function useSubmitBulkErrorClass() {
  const queryClient = useQueryClient()
  return useMutation<BulkJobDto, ActionError, BulkErrorClassRequest>({
    retry: false,
    mutationFn: submitBulkErrorClass,
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: BULK_JOBS_KEY })
    },
  })
}

export function useSubmitBulkFilter() {
  const queryClient = useQueryClient()
  return useMutation<BulkJobDto, ActionError, BulkFilterRequest>({
    retry: false,
    mutationFn: submitBulkFilter,
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: BULK_JOBS_KEY })
    },
  })
}

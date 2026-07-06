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
 * refresh); polling tightens only while a job is actually live.
 */
export function useBulkJobs(enabled: boolean) {
  return useQuery({
    queryKey: BULK_JOBS_KEY,
    queryFn: fetchBulkJobs,
    enabled,
    refetchInterval: (query) => ((query.state.data ?? []).some(jobActive) ? 2500 : 30_000),
  })
}

export function useBulkJob(id: string | undefined) {
  return useQuery({
    queryKey: ['bulk-job', id],
    queryFn: () => fetchBulkJob(id ?? ''),
    enabled: id !== undefined,
    refetchInterval: (query) =>
      query.state.data !== undefined && jobActive(query.state.data) ? 1500 : false,
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

// v2 Registry CRUD admin surface (docs/REGISTRY-CRUD.md §9/§11). The runtime engine-registry
// lifecycle — REGISTRY_ADMIN only, greyed-never-hidden in the nav. Every call goes through the
// generated openapi-fetch client; types come from schema.d.ts (never hand-written).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type { components } from '../api/schema'

export type AdminEngineDto = components['schemas']['AdminEngineDto']
export type EngineWriteRequest = components['schemas']['EngineWriteRequest']
export type DriftReport = components['schemas']['DriftReport']

const ENGINES_KEY = ['adminEngines'] as const
const DRIFT_KEY = ['adminEnginesDrift'] as const

async function listEngines(): Promise<AdminEngineDto[]> {
  const { data, error, response } = await api.GET('/api/admin/engines')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function fetchDrift(): Promise<DriftReport> {
  const { data, error, response } = await api.GET('/api/admin/engines/drift')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function addEngine(body: EngineWriteRequest): Promise<AdminEngineDto> {
  const { data, error, response } = await api.POST('/api/admin/engines', { body })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function editEngine(id: string, body: EngineWriteRequest): Promise<AdminEngineDto> {
  const { data, error, response } = await api.PUT('/api/admin/engines/{id}', {
    params: { path: { id } },
    body,
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function probeEngine(id: string): Promise<AdminEngineDto> {
  const { data, error, response } = await api.POST('/api/admin/engines/{id}/probe', {
    params: { path: { id } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function enableEngine(
  id: string,
  body: { readWrite: boolean; confirmToken: string; reason: string },
): Promise<AdminEngineDto> {
  const { data, error, response } = await api.POST('/api/admin/engines/{id}/enable', {
    params: { path: { id } },
    body,
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function disableEngine(id: string, reason: string): Promise<AdminEngineDto> {
  const { data, error, response } = await api.POST('/api/admin/engines/{id}/disable', {
    params: { path: { id } },
    body: { reason },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function removeEngine(id: string, confirmToken: string, reason: string): Promise<void> {
  const { error, response } = await api.DELETE('/api/admin/engines/{id}', {
    params: { path: { id } },
    body: { confirmToken, reason },
  })
  if (!response.ok) throw new ApiError(response.status, error)
}

async function purgeEngine(id: string, confirmToken: string, reason: string): Promise<void> {
  const { error, response } = await api.POST('/api/admin/engines/{id}/purge', {
    params: { path: { id } },
    body: { confirmToken, reason },
  })
  if (!response.ok) throw new ApiError(response.status, error)
}

export function useAdminEngines() {
  return useQuery({ queryKey: ENGINES_KEY, queryFn: listEngines, retry: false })
}

export function useDrift() {
  return useQuery({ queryKey: DRIFT_KEY, queryFn: fetchDrift, retry: false })
}

/** All the lifecycle mutations, each invalidating the list + drift so the table reflects reality. */
export function useEngineMutations() {
  const queryClient = useQueryClient()
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ENGINES_KEY })
    void queryClient.invalidateQueries({ queryKey: DRIFT_KEY })
  }
  return {
    add: useMutation({ mutationFn: addEngine, onSuccess: invalidate }),
    edit: useMutation({
      mutationFn: (vars: { id: string; body: EngineWriteRequest }) =>
        editEngine(vars.id, vars.body),
      onSuccess: invalidate,
    }),
    probe: useMutation({ mutationFn: probeEngine, onSuccess: invalidate }),
    enable: useMutation({
      mutationFn: (vars: {
        id: string
        readWrite: boolean
        confirmToken: string
        reason: string
      }) =>
        enableEngine(vars.id, {
          readWrite: vars.readWrite,
          confirmToken: vars.confirmToken,
          reason: vars.reason,
        }),
      onSuccess: invalidate,
    }),
    disable: useMutation({
      mutationFn: (vars: { id: string; reason: string }) => disableEngine(vars.id, vars.reason),
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: (vars: { id: string; confirmToken: string; reason: string }) =>
        removeEngine(vars.id, vars.confirmToken, vars.reason),
      onSuccess: invalidate,
    }),
    purge: useMutation({
      mutationFn: (vars: { id: string; confirmToken: string; reason: string }) =>
        purgeEngine(vars.id, vars.confirmToken, vars.reason),
      onSuccess: invalidate,
    }),
  }
}

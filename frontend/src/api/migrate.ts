// v2 instance migration dispatch: the two dedicated whitelisted routes (migrate
// preview/execute) + the definition-versions on-ramp read. The preview is a BFF STATIC
// auto-map check (Flowable's REST API exposes no migration validator — P0 spike), an
// Inspector estimate, never an engine validation. Execute is the one real engine call;
// simulation-first (execute is reachable only through the previewed modal) and never
// optimistic — it re-fetches every instance segment + audit on settle.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { parseActionProblem } from '../actions/problem'
import { ActionError } from './actions'
import type { ActionResult } from './actions'
import { ApiError, api } from './client'
import type { components } from './schema'

export type MigrationRequest = components['schemas']['MigrationRequest']
export type MigrationPreview = components['schemas']['MigrationPreview']
export type MigrationMapping = components['schemas']['MigrationMapping']
export type ActivityDiffEntry = components['schemas']['ActivityDiffEntry']
export type TargetActivity = components['schemas']['TargetActivity']
export type DefinitionVersionsResponse = components['schemas']['DefinitionVersionsResponse']
export type DefinitionVersion = components['schemas']['DefinitionVersion']

export async function previewMigration(
  engineId: string,
  instanceId: string,
  body: MigrationRequest,
): Promise<MigrationPreview> {
  const { data, error, response } = await api.POST(
    '/api/instances/{engineId}/{instanceId}/migrate/preview',
    { params: { path: { engineId, instanceId } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function executeMigration(
  engineId: string,
  instanceId: string,
  body: MigrationRequest,
): Promise<ActionResult> {
  const { data, error, response } = await api.POST(
    '/api/instances/{engineId}/{instanceId}/migrate/execute',
    { params: { path: { engineId, instanceId } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function fetchDefinitionVersions(
  engineId: string,
  key: string,
): Promise<DefinitionVersionsResponse> {
  const { data, error, response } = await api.GET('/api/definitions/{engineId}/{key}/versions', {
    params: { path: { engineId, key } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Read-only BFF static pre-check — no invalidation; the refused/failed problem still surfaces. */
export function useMigratePreview(engineId: string, instanceId: string) {
  return useMutation<MigrationPreview, ActionError, MigrationRequest>({
    retry: false,
    mutationFn: (body) => previewMigration(engineId, instanceId, body),
  })
}

export function useMigrateExecute(engineId: string, instanceId: string) {
  const queryClient = useQueryClient()
  return useMutation<ActionResult, ActionError, MigrationRequest>({
    retry: false,
    mutationFn: (body) => executeMigration(engineId, instanceId, body),
    onSettled: async () => {
      // Re-fetch server truth — NEVER optimistic (corrective-actions §4).
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['instance', engineId, instanceId] }),
        queryClient.invalidateQueries({ queryKey: ['audit', engineId, instanceId] }),
        queryClient.invalidateQueries({ queryKey: ['engines'] }),
      ])
    },
  })
}

/** The definition-versions on-ramp read. Fires only when a key is known. */
export function useDefinitionVersions(engineId: string, key: string | undefined) {
  return useQuery<DefinitionVersionsResponse, ApiError>({
    queryKey: ['definition-versions', engineId, key],
    queryFn: () => fetchDefinitionVersions(engineId, key ?? ''),
    enabled: key !== undefined && key !== '',
    staleTime: 15_000,
  })
}

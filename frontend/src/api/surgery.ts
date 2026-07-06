// v1.1 Flow Surgery dispatch: the three dedicated whitelisted routes (change-state
// preview/execute, restart-as-new) — never the generic {verb} endpoint. Simulation-first:
// execute is reachable only through the verification modal that rendered the preview.
// Mutations are never auto-retried and never optimistic; execute/restart re-fetch every
// instance segment plus the audit trail on settle (corrective-actions skill §4). The
// preview is a BFF-side simulation — the engine is untouched — so it invalidates nothing.
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { parseActionProblem } from '../actions/problem'
import { ActionError } from './actions'
import type { ActionResult } from './actions'
import { api } from './client'
import type { components } from './schema'

export type ChangeStateRequest = components['schemas']['ChangeStateRequest']
export type ChangeStatePreview = components['schemas']['ChangeStatePreview']
export type RestartInstanceRequest = components['schemas']['RestartInstanceRequest']
export type RestartInstanceResult = components['schemas']['RestartInstanceResult']

export async function previewChangeState(
  engineId: string,
  instanceId: string,
  body: ChangeStateRequest,
): Promise<ChangeStatePreview> {
  const { data, error, response } = await api.POST(
    '/api/instances/{engineId}/{instanceId}/change-state/preview',
    { params: { path: { engineId, instanceId } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function executeChangeState(
  engineId: string,
  instanceId: string,
  body: ChangeStateRequest,
): Promise<ActionResult> {
  const { data, error, response } = await api.POST(
    '/api/instances/{engineId}/{instanceId}/change-state/execute',
    { params: { path: { engineId, instanceId } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function restartInstance(
  engineId: string,
  instanceId: string,
  body: RestartInstanceRequest,
): Promise<RestartInstanceResult> {
  const { data, error, response } = await api.POST(
    '/api/instances/{engineId}/{instanceId}/restart',
    { params: { path: { engineId, instanceId } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

/** Read-only BFF simulation — no invalidation; the refused/failed problem still surfaces. */
export function useChangeStatePreview(engineId: string, instanceId: string) {
  return useMutation<ChangeStatePreview, ActionError, ChangeStateRequest>({
    retry: false,
    mutationFn: (body) => previewChangeState(engineId, instanceId, body),
  })
}

function useSurgeryInvalidation(engineId: string, instanceId: string) {
  const queryClient = useQueryClient()
  return async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['instance', engineId, instanceId] }),
      queryClient.invalidateQueries({ queryKey: ['audit', engineId, instanceId] }),
      queryClient.invalidateQueries({ queryKey: ['engines'] }),
    ])
  }
}

export function useChangeStateExecute(engineId: string, instanceId: string) {
  const invalidate = useSurgeryInvalidation(engineId, instanceId)
  return useMutation<ActionResult, ActionError, ChangeStateRequest>({
    retry: false,
    mutationFn: (body) => executeChangeState(engineId, instanceId, body),
    onSettled: invalidate,
  })
}

export function useRestartInstance(engineId: string, instanceId: string) {
  const invalidate = useSurgeryInvalidation(engineId, instanceId)
  return useMutation<RestartInstanceResult, ActionError, RestartInstanceRequest>({
    retry: false,
    mutationFn: (body) => restartInstance(engineId, instanceId, body),
    onSettled: invalidate,
  })
}

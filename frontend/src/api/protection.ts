// R-SAFE-05 write path (#165): mark/unmark an instance protected. Both are BFF-store-only
// (no engine call) — ADMIN-per-engine gated server-side, reason required either way. Mutations
// are never optimistic; on settle we re-fetch the instance + audit trail + engines (the grid's
// badge and the vitals-header badge both come from these) rather than assuming success.
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { parseActionProblem } from '../actions/problem'
import { ActionError } from './actions'
import { api } from './client'
import type { components } from './schema'

export type ProtectionRequest = components['schemas']['ProtectionRequest']

async function protectInstance(
  engineId: string,
  instanceId: string,
  body: ProtectionRequest,
): Promise<null> {
  const { error, response } = await api.POST('/api/instances/{engineId}/{instanceId}/protect', {
    params: { path: { engineId, instanceId } },
    body,
  })
  if (!response.ok) throw new ActionError(parseActionProblem(response.status, error))
  return null
}

async function unprotectInstance(
  engineId: string,
  instanceId: string,
  body: ProtectionRequest,
): Promise<null> {
  const { error, response } = await api.POST('/api/instances/{engineId}/{instanceId}/unprotect', {
    params: { path: { engineId, instanceId } },
    body,
  })
  if (!response.ok) throw new ActionError(parseActionProblem(response.status, error))
  return null
}

function useProtectionInvalidation(engineId: string, instanceId: string) {
  const queryClient = useQueryClient()
  return async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['instance', engineId, instanceId] }),
      queryClient.invalidateQueries({ queryKey: ['audit', engineId, instanceId] }),
      queryClient.invalidateQueries({ queryKey: ['engines'] }),
    ])
  }
}

export function useProtectInstance(engineId: string, instanceId: string) {
  const invalidate = useProtectionInvalidation(engineId, instanceId)
  return useMutation<null, ActionError, ProtectionRequest>({
    retry: false,
    mutationFn: (body) => protectInstance(engineId, instanceId, body),
    onSettled: invalidate,
  })
}

export function useUnprotectInstance(engineId: string, instanceId: string) {
  const invalidate = useProtectionInvalidation(engineId, instanceId)
  return useMutation<null, ActionError, ProtectionRequest>({
    retry: false,
    mutationFn: (body) => unprotectInstance(engineId, instanceId, body),
    onSettled: invalidate,
  })
}

// #172: definition-key scope, deferred from #165. Same BFF-store-only shape — no engine call,
// ADMIN-per-engine gated server-side, reason required either way.
async function protectDefinition(
  engineId: string,
  definitionKey: string,
  body: ProtectionRequest,
): Promise<null> {
  const { error, response } = await api.POST(
    '/api/definitions/{engineId}/{definitionKey}/protect',
    {
      params: { path: { engineId, definitionKey } },
      body,
    },
  )
  if (!response.ok) throw new ActionError(parseActionProblem(response.status, error))
  return null
}

async function unprotectDefinition(
  engineId: string,
  definitionKey: string,
  body: ProtectionRequest,
): Promise<null> {
  const { error, response } = await api.POST(
    '/api/definitions/{engineId}/{definitionKey}/unprotect',
    {
      params: { path: { engineId, definitionKey } },
      body,
    },
  )
  if (!response.ok) throw new ActionError(parseActionProblem(response.status, error))
  return null
}

function useDefinitionProtectionInvalidation(engineId: string, definitionKey: string) {
  const queryClient = useQueryClient()
  return async () => {
    await queryClient.invalidateQueries({
      queryKey: ['definition-versions', engineId, definitionKey],
    })
  }
}

export function useProtectDefinition(engineId: string, definitionKey: string) {
  const invalidate = useDefinitionProtectionInvalidation(engineId, definitionKey)
  return useMutation<null, ActionError, ProtectionRequest>({
    retry: false,
    mutationFn: (body) => protectDefinition(engineId, definitionKey, body),
    onSettled: invalidate,
  })
}

export function useUnprotectDefinition(engineId: string, definitionKey: string) {
  const invalidate = useDefinitionProtectionInvalidation(engineId, definitionKey)
  return useMutation<null, ActionError, ProtectionRequest>({
    retry: false,
    mutationFn: (body) => unprotectDefinition(engineId, definitionKey, body),
    onSettled: invalidate,
  })
}

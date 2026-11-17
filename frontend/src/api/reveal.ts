// #743: the audited reveal of ONE secret-named variable value. A plain POST, deliberately NOT a
// TanStack query or mutation: the value must live in the calling component's state only, never in
// a cache that outlives it.
import { api, ApiError } from './client'
import type { RevealedVariable } from './model'

export interface RevealTarget {
  kind: 'instance' | 'case'
  engineId: string
  targetId: string
}

export async function revealVariable(
  target: RevealTarget,
  name: string,
  executionId?: string,
  reason?: string,
): Promise<RevealedVariable> {
  const body = reason !== undefined ? { reason } : {}
  const { data, error, response } =
    target.kind === 'case'
      ? await api.POST('/api/cases/{engineId}/{caseInstanceId}/variables/{name}/reveal', {
          params: { path: { engineId: target.engineId, caseInstanceId: target.targetId, name } },
          body,
        })
      : await api.POST('/api/instances/{engineId}/{instanceId}/variables/{name}/reveal', {
          params: {
            path: { engineId: target.engineId, instanceId: target.targetId, name },
            ...(executionId !== undefined ? { query: { executionId } } : {}),
          },
          body,
        })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

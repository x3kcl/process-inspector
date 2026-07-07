// Corrective-action dispatch (M4). One fetcher over the single {verb} endpoint; failures
// become ActionError carrying the parsed ProblemDetail so the guard-ladder UI can keep
// the refused / engine-rejected / dispatched-unverified distinction visible.
// Mutations are NEVER auto-retried (corrective-actions skill §4) and never optimistic:
// callers re-fetch server truth via useInstanceAction's invalidation.
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { ActionProblem } from '../actions/problem'
import { parseActionProblem } from '../actions/problem'
import { api } from './client'
import type { components } from './schema'

export type ActionRequest = components['schemas']['ActionRequest']
export type ActionResult = components['schemas']['ActionResult']
export type ActionCurlResponse = components['schemas']['ActionCurlResponse']

export class ActionError extends Error {
  readonly problem: ActionProblem

  constructor(problem: ActionProblem) {
    super(problem.detail !== '' ? problem.detail : problem.title)
    this.name = 'ActionError'
    this.problem = problem
  }
}

export async function dispatchInstanceAction(
  engineId: string,
  instanceId: string,
  verb: string,
  body: ActionRequest,
): Promise<ActionResult> {
  const { data, error, response } = await api.POST(
    '/api/instances/{engineId}/{instanceId}/actions/{verb}',
    { params: { path: { engineId, instanceId, verb } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

/**
 * "Show as cURL" (v1.x #6). The BFF renders the exact command it would dispatch for this
 * proposed action — its OWN endpoint, a placeholder credential, never a live token — and the
 * UI shows it verbatim. Server-computed, never recomputed client-side (same invariant as the
 * search cURL). Runs the same RBAC door as execute but touches neither engine nor audit.
 */
export async function fetchActionCurl(
  engineId: string,
  instanceId: string,
  verb: string,
  body: ActionRequest,
): Promise<ActionCurlResponse> {
  const { data, error, response } = await api.POST(
    '/api/instances/{engineId}/{instanceId}/actions/{verb}/curl',
    { params: { path: { engineId, instanceId, verb } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export interface ActionInput {
  verb: string
  body: ActionRequest
}

/**
 * The one mutation hook for instance verbs. No retry, no optimistic update; whatever
 * happened (success, refusal, even UNKNOWN — the pre-flight read may have moved state),
 * every instance segment plus the audit trail is re-fetched so the UI renders only
 * server truth (corrective-actions skill §4).
 */
export function useInstanceAction(engineId: string, instanceId: string) {
  const queryClient = useQueryClient()
  return useMutation<ActionResult, ActionError, ActionInput>({
    retry: false,
    mutationFn: ({ verb, body }) => dispatchInstanceAction(engineId, instanceId, verb, body),
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['instance', engineId, instanceId] }),
        queryClient.invalidateQueries({ queryKey: ['audit', engineId, instanceId] }),
        queryClient.invalidateQueries({ queryKey: ['engines'] }),
      ])
    },
  })
}

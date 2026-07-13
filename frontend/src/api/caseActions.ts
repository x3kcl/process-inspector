// CMMN case corrective-action dispatch (Case Inspector Phase 3). The scope sibling of
// api/actions.ts: the SAME ActionRequest/ActionResult/ProblemDetail contract and the same
// never-auto-retry, never-optimistic rules — only the route differs (/api/cases/… instead of
// /api/instances/…), because the BFF reads/moves the CMMN (/cmmn-management) DLQ projection.
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { parseActionProblem } from '../actions/problem'
import { ActionError } from './actions'
import type { ActionCurlResponse, ActionRequest, ActionResult } from './actions'
import { api } from './client'

export async function dispatchCaseAction(
  engineId: string,
  caseInstanceId: string,
  verb: string,
  body: ActionRequest,
): Promise<ActionResult> {
  const { data, error, response } = await api.POST(
    '/api/cases/{engineId}/{caseInstanceId}/actions/{verb}',
    { params: { path: { engineId, caseInstanceId, verb } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

/**
 * "Show as cURL", CMMN scope sibling of api/actions.ts's fetchActionCurl (issue #103) —
 * same server-computed, placeholder-credential, never-recomputed-client-side invariant.
 */
export async function fetchCaseActionCurl(
  engineId: string,
  caseInstanceId: string,
  verb: string,
  body: ActionRequest,
): Promise<ActionCurlResponse> {
  const { data, error, response } = await api.POST(
    '/api/cases/{engineId}/{caseInstanceId}/actions/{verb}/curl',
    { params: { path: { engineId, caseInstanceId, verb } }, body },
  )
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export interface CaseActionInput {
  verb: string
  body: ActionRequest
}

/**
 * The one mutation hook for CMMN case verbs (Phase 3: dead-letter retry). No retry, no
 * optimistic update; every case segment plus the audit trail and engine health is re-fetched
 * so the UI renders only server truth (corrective-actions skill §4).
 */
export function useCaseAction(engineId: string, caseInstanceId: string) {
  const queryClient = useQueryClient()
  return useMutation<ActionResult, ActionError, CaseActionInput>({
    retry: false,
    mutationFn: ({ verb, body }) => dispatchCaseAction(engineId, caseInstanceId, verb, body),
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['case', engineId, caseInstanceId] }),
        queryClient.invalidateQueries({ queryKey: ['audit', engineId, caseInstanceId] }),
        queryClient.invalidateQueries({ queryKey: ['engines'] }),
      ])
    },
  })
}

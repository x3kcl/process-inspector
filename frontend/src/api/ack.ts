// R-BAU-01 error-group acknowledge (SPEC §4 Stage 0). NO OPTIMISTIC STATE: the mutation
// inserts nothing client-side — the triage query re-fetches and the server's render-time
// join decides collapsed/resurfaced. Coordinates only cross the wire (baselines resolve
// server-side, mirroring the group retry); mutations never retry.
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ActionError } from './actions'
import { api } from './client'
import { parseActionProblem } from '../actions/problem'
import type { components } from './schema'

export type AcknowledgeErrorGroupRequest = components['schemas']['AcknowledgeErrorGroupRequest']
export type UnacknowledgeErrorGroupRequest = components['schemas']['UnacknowledgeErrorGroupRequest']
export type ErrorGroupAcknowledgement = components['schemas']['ErrorGroupAcknowledgement']

export async function acknowledgeErrorGroup(
  body: AcknowledgeErrorGroupRequest,
): Promise<ErrorGroupAcknowledgement> {
  const { data, error, response } = await api.POST('/api/triage/error-groups/acknowledge', {
    body,
  })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function unacknowledgeErrorGroup(body: UnacknowledgeErrorGroupRequest): Promise<null> {
  // 204 on success — check the response, not the (absent) body.
  const { error, response } = await api.POST('/api/triage/error-groups/unacknowledge', { body })
  if (!response.ok) throw new ActionError(parseActionProblem(response.status, error))
  return null
}

export function useAcknowledgeErrorGroup() {
  const queryClient = useQueryClient()
  return useMutation<ErrorGroupAcknowledgement, ActionError, AcknowledgeErrorGroupRequest>({
    retry: false,
    mutationFn: acknowledgeErrorGroup,
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: ['triage'] })
    },
  })
}

export function useUnacknowledgeErrorGroup() {
  const queryClient = useQueryClient()
  return useMutation<null, ActionError, UnacknowledgeErrorGroupRequest>({
    retry: false,
    mutationFn: unacknowledgeErrorGroup,
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: ['triage'] })
    },
  })
}

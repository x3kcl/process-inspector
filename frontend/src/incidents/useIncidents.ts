// The Incident Ledger's TanStack Query surface (R-BAU-10, docs/INCIDENT-LEDGER.md §6/§8).
// Reads mirror triage/useTriage.ts (plain ApiError, gentle polling, the server's own
// aggregation/window semantics — no client-side re-derivation). The two lifecycle verbs
// mirror api/ack.ts's mutation idiom: BFF-store-only config events, so failures come back
// as the SAME RFC-7807 ProblemDetail shape (ActionError/parseActionProblem), never a plain
// ApiError, and NO optimistic state — a settled mutation just invalidates server truth.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ActionError } from '../api/actions'
import { api } from '../api/client'
import { fetchIncident, fetchIncidents } from '../api/queries'
import { parseActionProblem } from '../actions/problem'
import type { components } from '../api/schema'

export type ResolveIncidentRequest = components['schemas']['ResolveIncidentRequest']
export type ReopenIncidentRequest = components['schemas']['ReopenIncidentRequest']
export type IncidentResolution = components['schemas']['IncidentResolution']
export type IncidentSummary = components['schemas']['IncidentSummary']

/**
 * The ledger list — unpaginated (bounded by distinct failure classes), scope-projected
 * server-side. Polls gently like Stage 0 (60s); the "Refresh" affordance the triage page has
 * bypasses ITS 20s cache, but the ledger carries no such cache, so this list needs none.
 */
export function useIncidents(state?: string, windowHours?: number) {
  return useQuery({
    queryKey: ['incidents', state ?? null, windowHours ?? null],
    queryFn: () => fetchIncidents(state, windowHours),
    refetchInterval: 60_000,
    staleTime: 15_000,
  })
}

/** One incident's full detail — episodes, occurrence series, the live Stage-0 join. */
export function useIncident(id: number, windowHours?: number) {
  return useQuery({
    queryKey: ['incident', id, windowHours ?? null],
    queryFn: () => fetchIncident(id, windowHours),
    refetchInterval: 60_000,
    staleTime: 15_000,
  })
}

async function resolveIncident(
  id: number,
  body: ResolveIncidentRequest,
): Promise<IncidentResolution> {
  const { data, error, response } = await api.POST('/api/incidents/{id}/resolve', {
    params: { path: { id } },
    body,
  })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

async function reopenIncident(id: number, body: ReopenIncidentRequest): Promise<IncidentSummary> {
  const { data, error, response } = await api.POST('/api/incidents/{id}/reopen', {
    params: { path: { id } },
    body,
  })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

/**
 * Both lifecycle verbs invalidate the SAME two query families regardless of which fired: the
 * list (every section a resolve/reopen can move an incident between) and this one incident's
 * own detail (episodes, lifecycle strip, ack state all change together).
 */
function invalidateIncident(queryClient: ReturnType<typeof useQueryClient>, id: number) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['incidents'] }),
    queryClient.invalidateQueries({ queryKey: ['incident', id] }),
  ])
}

export function useResolveIncident(id: number) {
  const queryClient = useQueryClient()
  return useMutation<IncidentResolution, ActionError, ResolveIncidentRequest>({
    retry: false,
    mutationFn: (body) => resolveIncident(id, body),
    onSettled: async () => {
      await invalidateIncident(queryClient, id)
    },
  })
}

export function useReopenIncident(id: number) {
  const queryClient = useQueryClient()
  return useMutation<IncidentSummary, ActionError, ReopenIncidentRequest>({
    retry: false,
    mutationFn: (body) => reopenIncident(id, body),
    onSettled: async () => {
      await invalidateIncident(queryClient, id)
    },
  })
}

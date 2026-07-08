import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchTriage, fetchTriageTrends } from '../api/queries'

/**
 * Stage 0 aggregations. The BFF already caches ~20s (thundering-herd protection, SPEC §4),
 * so the client polls gently and shows the server's asOf stamp; the Refresh button is the
 * only cache bypass and stays rate-limited server-side.
 */
export function useTriage() {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['triage'],
    queryFn: () => fetchTriage(false),
    refetchInterval: 60_000,
    staleTime: 15_000,
  })
  const refresh = useCallback(() => {
    return queryClient.fetchQuery({
      queryKey: ['triage'],
      queryFn: () => fetchTriage(true),
      staleTime: 0,
    })
  }, [queryClient])
  return { ...query, refresh }
}

/**
 * v2/M4 job-lane trend history (R-BAU-08) for the Stage-0 sparklines. Reads the snapshot store,
 * not the live engine, so it polls gently and never blocks the landing — a failure just hides
 * the sparklines (the counts still render).
 */
export function useTriageTrends(hours = 24) {
  return useQuery({
    queryKey: ['triage-trends', hours],
    queryFn: () => fetchTriageTrends(hours),
    refetchInterval: 60_000,
    staleTime: 30_000,
  })
}

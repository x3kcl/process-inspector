import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchTriage } from '../api/queries'

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

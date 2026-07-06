import { useQuery } from '@tanstack/react-query'
import { fetchEngines } from './queries'

/**
 * The ONE engines query of the app (SPEC §10: polling drives v1 liveness; SSE is v1.x).
 * Every consumer shares this key, so N components never multiply the 30s poll.
 */
export function useEngines() {
  return useQuery({
    queryKey: ['engines'],
    queryFn: fetchEngines,
    refetchInterval: 30_000,
  })
}

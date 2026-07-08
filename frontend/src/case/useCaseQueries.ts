// Case Inspector Phase 2 data hooks — one TanStack Query per case-detail endpoint. Mirrors
// useInstanceQueries: read-only, per-segment, staleTime keeps navigation snappy.
import { useQuery } from '@tanstack/react-query'
import { fetchCaseDiagram, fetchCasePlanItems, fetchCaseVitals } from '../api/queries'

const STALE_MS = 15_000

function key(engineId: string, caseInstanceId: string, segment: string) {
  return ['case', engineId, caseInstanceId, segment]
}

export function useCaseVitals(engineId: string, caseInstanceId: string) {
  return useQuery({
    queryKey: key(engineId, caseInstanceId, 'vitals'),
    queryFn: () => fetchCaseVitals({ engineId, caseInstanceId }),
    staleTime: STALE_MS,
  })
}

export function useCaseDiagram(engineId: string, caseInstanceId: string) {
  return useQuery({
    queryKey: key(engineId, caseInstanceId, 'diagram'),
    queryFn: () => fetchCaseDiagram({ engineId, caseInstanceId }),
    staleTime: STALE_MS,
  })
}

export function useCasePlanItems(engineId: string, caseInstanceId: string) {
  return useQuery({
    queryKey: key(engineId, caseInstanceId, 'plan-items'),
    queryFn: () => fetchCasePlanItems({ engineId, caseInstanceId }),
    staleTime: STALE_MS,
  })
}

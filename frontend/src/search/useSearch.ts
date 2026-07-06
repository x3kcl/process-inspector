import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import type { SearchRequest } from '../api/model'
import { runSearch } from '../api/queries'
import { decodeSearch, encodeSearch } from './urlState'

/** The URL is the search state (SPEC §4): submitting a form only rewrites the params. */
export function useSearchUrl() {
  const [params, setParams] = useSearchParams()
  const paramsKey = params.toString()
  const request = useMemo(() => decodeSearch(new URLSearchParams(paramsKey)), [paramsKey])
  const submit = useCallback(
    (next: SearchRequest) => {
      setParams(encodeSearch(next))
    },
    [setParams],
  )
  return { request, submit, paramsKey }
}

export function useSearchResults(request: SearchRequest | null) {
  return useQuery({
    queryKey: ['search', request],
    queryFn: () => {
      if (request === null) throw new Error('search is disabled without criteria')
      return runSearch(request)
    },
    enabled: request !== null,
    // Snapshot semantics (SPEC §4): results never silently go stale-refetch;
    // the "Refresh" button is the only re-run.
    staleTime: Number.POSITIVE_INFINITY,
  })
}

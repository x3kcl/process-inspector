import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import type { ProcessInstanceRow, SearchRequest, SearchResponse } from '../api/model'
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

/**
 * The result set as a "Load more" chain (v2 deep paging, docs/KWAY-PAGING.md). Page 1 is the
 * ordinary single-shot search; when it OVERFLOWS a time-ordered result set the BFF hands back a
 * `nextCursor`, and each "Load more" click fetches the next globally-sorted page via the same
 * endpoint with that opaque cursor. ~95% of searches never overflow, so `hasNextPage` is false and
 * this behaves exactly like the old single query. The authoritative filter is always re-sent in the
 * body; the cursor only carries the resume offsets.
 */
export function useSearchResults(request: SearchRequest | null) {
  const queryClient = useQueryClient()
  const queryKey = useMemo(() => ['search', request] as const, [request])
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }) => {
      if (request === null) throw new Error('search is disabled without criteria')
      return runSearch({ ...request, cursor: pageParam })
    },
    enabled: request !== null,
    // Snapshot semantics (SPEC §4): results never silently go stale-refetch; Refresh (which resets
    // the whole chain) is the only re-run, and a deep-paged set is explicitly a snapshot.
    staleTime: Number.POSITIVE_INFINITY,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: SearchResponse) => last.nextCursor ?? undefined,
  })

  const pages = query.data?.pages
  const data = useMemo<SearchResponse | undefined>(() => {
    if (pages === undefined || pages.length === 0) return undefined
    // Flatten into one grid feed, de-duped by compositeId (globally unique; the server also bounds
    // duplicate emission to one sort-key value — this keeps counts honest and matches AG Grid's getRowId).
    const seen = new Set<string>()
    const rows: ProcessInstanceRow[] = []
    for (const page of pages) {
      for (const row of page.rows ?? []) {
        const id = row.compositeId ?? row.processInstanceId ?? ''
        if (id !== '' && seen.has(id)) continue
        if (id !== '') seen.add(id)
        rows.push(row)
      }
    }
    // perEngine / statusCounts / criteriaEcho / curl come from PAGE 1 — the original overflow context
    // and facet counts, stable across "Load more" (a deep page's perEngine only sees its own fan-out,
    // and its statusCounts are empty). The deep-paging markers come from the LAST (freshest) page.
    const first = pages[0]
    const last = pages[pages.length - 1]
    // #167: `fetched` is the one perEngine field that must NOT stay frozen at page 1 — it's the
    // "X of Y fetched" progress readout, and Load-more exists specifically to grow it. Recompute
    // it from the accumulated, de-duped row set (never trust summing each page's own `fetched`,
    // which would double-count rows re-emitted across a boundary tie-cluster); `total` and every
    // other field stay from page 1 as before.
    const fetchedByEngine = new Map<string, number>()
    for (const row of rows) {
      if (row.engineId === undefined) continue
      fetchedByEngine.set(row.engineId, (fetchedByEngine.get(row.engineId) ?? 0) + 1)
    }
    const perEngine =
      first.perEngine === undefined
        ? first.perEngine
        : Object.fromEntries(
            Object.entries(first.perEngine).map(([engineId, result]) => [
              engineId,
              result.ok === true
                ? { ...result, fetched: fetchedByEngine.get(engineId) ?? result.fetched }
                : result,
            ]),
          )
    return {
      ...first,
      perEngine,
      rows,
      nextCursor: last.nextCursor,
      depthCapped: last.depthCapped,
      pagingCoherence: last.pagingCoherence,
    }
  }, [pages])

  // Refresh RESETS the deep-paging chain to page 1 (docs/KWAY-PAGING.md §4): resetQueries drops every
  // accumulated page and refetches from initialPageParam, so we never re-run a stored deep cursor
  // (useInfiniteQuery.refetch() would re-run ALL pages — a stale/expired cursor there would 400).
  const refresh = useCallback(() => {
    void queryClient.resetQueries({ queryKey })
  }, [queryClient, queryKey])

  return { ...query, data, refresh }
}

// Server-backed Saved Views + Recent Searches (v2/M4, SPEC §8) — the successor to the v1.x
// localStorage stores. The BFF keys every row on the authenticated user, so views/recents now
// follow a user across browsers. Views/recents still store CANONICAL search strings
// (normalizeSearch) so highlight matching and server-side dedupe never depend on param order.
import { useCallback } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { RecentSearchDto, SavedViewDto, SearchRequest } from '../api/model'
import {
  deleteSavedView,
  fetchRecents,
  fetchSavedViews,
  postRecent,
  putSavedView,
} from '../api/queries'
import { describeSearch, normalizeSearch } from './model'

const SAVED_VIEWS_KEY = ['savedViews'] as const
const RECENTS_KEY = ['recents'] as const

export interface SavedViewsApi {
  views: SavedViewDto[]
  save: (name: string, search: string) => void
  remove: (id: number) => void
}

export function useSavedViews(): SavedViewsApi {
  const queryClient = useQueryClient()
  const { data } = useQuery({ queryKey: SAVED_VIEWS_KEY, queryFn: fetchSavedViews })
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: SAVED_VIEWS_KEY })

  const saveMutation = useMutation({
    mutationFn: (vars: { name: string; search: string }) =>
      putSavedView(vars.name.trim(), normalizeSearch(vars.search)),
    onSuccess: invalidate,
  })
  const removeMutation = useMutation({
    mutationFn: (id: number) => deleteSavedView(id),
    onSuccess: invalidate,
  })

  return {
    views: data ?? [],
    save: (name, search) => {
      saveMutation.mutate({ name, search })
    },
    remove: (id) => {
      removeMutation.mutate(id)
    },
  }
}

export function useRecentSearches(): RecentSearchDto[] {
  const { data } = useQuery({ queryKey: RECENTS_KEY, queryFn: fetchRecents })
  return data ?? []
}

/**
 * Stage 1 write path: record a search AFTER it executed successfully (never on typing). Returns a
 * STABLE callback (safe in an effect's deps). Best-effort — a failed record must never surface as
 * a search error; it just doesn't reach the recents list.
 */
export function useRecordRecentSearch(): (search: string, request: SearchRequest) => void {
  const queryClient = useQueryClient()
  return useCallback(
    (search: string, request: SearchRequest) => {
      void postRecent(normalizeSearch(search), describeSearch(request))
        .then(() => queryClient.invalidateQueries({ queryKey: RECENTS_KEY }))
        .catch(() => {
          /* best-effort: recording history must never break the search itself */
        })
    },
    [queryClient],
  )
}

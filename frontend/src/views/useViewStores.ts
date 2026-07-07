// The two v1.x localStorage stores (SPEC §8: localStorage v1.x → shared server-side v2)
// and their React bindings. Views/recents store CANONICAL search strings (normalizeSearch)
// so highlight matching and recents dedup never depend on param order.
import { useSyncExternalStore } from 'react'
import type { SearchRequest } from '../api/model'
import { createLocalStore } from './localStore'
import type { RecentSearch, SavedView } from './model'
import {
  describeSearch,
  isRecentSearch,
  isSavedView,
  normalizeSearch,
  pushRecent,
  removeView,
  upsertView,
} from './model'

const savedViewsStore = createLocalStore<SavedView>('inspector.savedViews', isSavedView)
const recentSearchesStore = createLocalStore<RecentSearch>(
  'inspector.recentSearches',
  isRecentSearch,
)

export interface SavedViewsApi {
  views: SavedView[]
  save: (name: string, search: string) => void
  remove: (id: string) => void
}

export function useSavedViews(): SavedViewsApi {
  const views = useSyncExternalStore(savedViewsStore.subscribe, savedViewsStore.read)
  return {
    views,
    save: (name: string, search: string) => {
      savedViewsStore.write(
        upsertView(savedViewsStore.read(), {
          id: crypto.randomUUID(),
          name: name.trim(),
          search: normalizeSearch(search),
          createdAt: new Date().toISOString(),
        }),
      )
    },
    remove: (id: string) => {
      savedViewsStore.write(removeView(savedViewsStore.read(), id))
    },
  }
}

export function useRecentSearches(): RecentSearch[] {
  return useSyncExternalStore(recentSearchesStore.subscribe, recentSearchesStore.read)
}

/** Write-only path for Stage 1: record AFTER a search executed successfully — never on typing. */
export function recordRecentSearch(search: string, request: SearchRequest): void {
  recentSearchesStore.write(
    pushRecent(recentSearchesStore.read(), {
      search: normalizeSearch(search),
      label: describeSearch(request),
      at: new Date().toISOString(),
    }),
  )
}

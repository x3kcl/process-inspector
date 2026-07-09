// One-time client migration of the v1 localStorage stores (Saved Views + Recent Searches) into
// the v2/M4 server store (SPEC §8). Best-effort: push each local entry to the BFF, then CLEAR the
// local keys so it never re-runs. A failure leaves the local data in place to retry next load —
// it never blocks the app and never throws. Server writes are idempotent (views upsert by name,
// recents dedupe by search), so a double-run is harmless.
import { useEffect } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { postRecent, putSavedView } from '../api/queries'
import { decodeEnvelope, isRecentSearch, isSavedView } from './model'

const SAVED_VIEWS_KEY = 'inspector.savedViews'
const RECENTS_KEY = 'inspector.recentSearches'

let started = false

/** Runs once, only when {@code enabled} (i.e. authenticated) — the server writes need a session. */
export function useLegacyViewMigration(enabled: boolean): void {
  const queryClient = useQueryClient()
  useEffect(() => {
    if (!enabled || started) return
    started = true
    void migrate(queryClient)
  }, [enabled, queryClient])
}

async function migrate(queryClient: QueryClient): Promise<void> {
  try {
    const rawViews = localStorage.getItem(SAVED_VIEWS_KEY)
    const rawRecents = localStorage.getItem(RECENTS_KEY)
    if (rawViews === null && rawRecents === null) return // nothing local → nothing to migrate

    // Push oldest-first so each entry's server timestamp orders it correctly (newest ends newest).
    const views = decodeEnvelope(rawViews, isSavedView).sort((a, b) =>
      a.createdAt.localeCompare(b.createdAt),
    )
    const recents = decodeEnvelope(rawRecents, isRecentSearch).sort((a, b) =>
      a.at.localeCompare(b.at),
    )

    for (const v of views) await putSavedView(v.name, v.search)
    for (const r of recents) await postRecent(r.search, r.label)

    // Only clear once the push succeeded — a partial/failed run retries on the next load.
    localStorage.removeItem(SAVED_VIEWS_KEY)
    localStorage.removeItem(RECENTS_KEY)
    await queryClient.invalidateQueries({ queryKey: ['savedViews'] })
    await queryClient.invalidateQueries({ queryKey: ['recents'] })
  } catch {
    started = false // allow a retry next load
  }
}

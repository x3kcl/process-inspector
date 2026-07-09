import { Link } from 'react-router'
import { RecentSearchList } from './RecentSearchList'
import { SYSTEM_VIEWS } from './systemViews'
import { useSavedViews } from './useViewStores'

/**
 * Stage 0 "Saved views" (SPEC §4): the curated system views beside the user-named ones,
 * plus the recent-search history. Every chip is a plain link into Stage 1 — clicking
 * replays the exact URL state (URL primacy); relative windows materialize at render time.
 */
export function SavedViewsSection() {
  const { views, remove } = useSavedViews()
  const now = new Date()
  return (
    <section className="saved-views" aria-label="Saved views">
      <h2>Saved views</h2>
      <div className="view-chips">
        {SYSTEM_VIEWS.map((view) => (
          <Link
            key={view.id}
            className="view-chip"
            title={view.note}
            to={`/search?${view.search(now)}`}
          >
            {view.name}
          </Link>
        ))}
        {views.map((view) => (
          <span key={view.id} className="view-chip user-view">
            <Link to={`/search?${view.search ?? ''}`}>{view.name}</Link>
            <button
              type="button"
              aria-label={`delete view ${view.name ?? ''}`}
              title="delete this view"
              onClick={() => {
                if (view.id !== undefined) remove(view.id)
              }}
            >
              ✕
            </button>
          </span>
        ))}
      </div>
      <RecentSearchList />
    </section>
  )
}

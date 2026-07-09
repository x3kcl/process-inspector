import { Link } from 'react-router'
import { PublishToTeamButton } from './PublishToTeamButton'
import { RecentSearchList } from './RecentSearchList'
import { SYSTEM_VIEWS } from './systemViews'
import { TeamViewsGroup } from './TeamViewsGroup'
import { useSavedViews } from './useViewStores'

/**
 * Stage 0 "Saved views" (SPEC §4, SHARED-VIEWS.md): the curated system views, then the team-published
 * canon (§4.6 precedence System → Team → Private), then the user-named ones, plus the recent-search
 * history. Every chip is a plain link into Stage 1 — clicking replays the exact URL state (URL
 * primacy). Each private view also carries the DELIBERATE "Publish to team…" second act
 * (greyed-never-hidden for non-OPERATORs).
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
      </div>
      <TeamViewsGroup />
      <div className="view-chips">
        {views.map((view) => (
          <span key={view.id} className="view-chip user-view">
            <Link to={`/search?${view.search ?? ''}`}>{view.name}</Link>
            {view.search !== undefined && view.name !== undefined && (
              <PublishToTeamButton name={view.name} search={view.search} />
            )}
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

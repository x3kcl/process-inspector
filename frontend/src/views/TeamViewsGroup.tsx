import { useState } from 'react'
import { Link } from 'react-router'
import { useMe } from '../api/me'
import { useTeamViews } from './useTeamViews'
import { isDangling, teamViewTitle } from './teamViewModel'

/**
 * The "Team views" group in the Stage-0 picker (SHARED-VIEWS.md §4.6): the curated canon the caller
 * may see, between the system windows and their private views (precedence System → Team → Private).
 * Each chip wears a non-color "TEAM" tag (R-UXQ-01: a bordered label, never hue-only) with author +
 * scope in the tooltip. A DANGLING canon (its scoped engine gone) is greyed and NON-clickable — never
 * a live link to a clean-looking "no failures" (§4.5).
 *
 * <p>The inline "✕" is the AUTHOR's reason-free self-service unpublish (the common, reversible case —
 * publish was a snapshot-copy, so the author keeps their private bookmark). It shows only on your own
 * canon (author === you): moderating ANOTHER's canon requires a reason ≥10 (SHARED-VIEWS.md §4.4), so
 * a bare ✕ can't do it — that moderator path is a separate affordance (follow-up), not a silent
 * no-op here. A real failure (audit down / network) on your own unpublish is surfaced, never swallowed.
 */
export function TeamViewsGroup() {
  const { views, unpublish } = useTeamViews()
  const { data: me } = useMe()
  const [error, setError] = useState<string | null>(null)
  if (views.length === 0) return null

  return (
    <div className="view-chips team-views" role="group" aria-label="Team views">
      {views.map((view) => {
        const dangling = isDangling(view)
        const isAuthor = me?.username !== undefined && me.username === view.author
        return (
          <span
            key={view.id}
            className={dangling ? 'view-chip team-view dangling' : 'view-chip team-view'}
            title={teamViewTitle(view)}
          >
            <span className="team-tag" aria-label="team view">
              TEAM
            </span>
            {dangling ? (
              <span className="team-view-name" aria-disabled="true">
                {view.name} <span className="team-view-note">(scope unavailable)</span>
              </span>
            ) : (
              <Link to={`/search?${view.search ?? ''}`}>{view.name}</Link>
            )}
            {isAuthor && (
              <button
                type="button"
                aria-label={`unpublish team view ${view.name ?? ''}`}
                title="remove your view from team views"
                onClick={() => {
                  if (view.id !== undefined) {
                    setError(null)
                    void unpublish(view.id).catch(() => {
                      setError(
                        `Couldn’t remove “${view.name ?? ''}” — the audit store may be down. Nothing changed.`,
                      )
                    })
                  }
                }}
              >
                ✕
              </button>
            )}
          </span>
        )
      })}
      {error !== null && (
        <p className="team-views-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

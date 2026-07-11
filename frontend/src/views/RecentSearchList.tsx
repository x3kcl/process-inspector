import { Link } from 'react-router'
import { Ts } from '../lib/Ts'
import { useRecentSearches } from './useViewStores'

/**
 * The last RECENT_CAP successfully executed searches, newest first (SPEC §4 Stage 0).
 * Each entry is a plain link back into Stage 1 — the stored search string IS the state.
 * Renders nothing while the history is empty.
 */
export function RecentSearchList() {
  const recents = useRecentSearches()
  if (recents.length === 0) return null
  return (
    <div className="recent-searches-block">
      <h3>Recent searches</h3>
      <ul className="recent-searches">
        {recents.map((recent) => (
          <li key={recent.search}>
            <Link to={`/search?${recent.search ?? ''}`}>{recent.label}</Link>
            <Ts className="recent-at" iso={recent.at} relative />
          </li>
        ))}
      </ul>
    </div>
  )
}

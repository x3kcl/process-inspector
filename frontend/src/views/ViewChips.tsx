import { Link } from 'react-router'
import { sameSearch } from './model'
import { SYSTEM_VIEWS } from './systemViews'
import { useSavedViews } from './useViewStores'

/**
 * Compact view strip for Stage 1. The chip whose canonical params EXACTLY match the
 * current URL is highlighted — no fuzzy matching (URL primacy: the URL is the state, the
 * chip is just its name). A relative-window system view therefore stops highlighting once
 * the minute it was materialized in has passed: the URL genuinely no longer means
 * "the last hour from now".
 */
export function ViewChips({ currentSearch }: { currentSearch: string }) {
  const { views } = useSavedViews()
  const now = new Date()
  const chips = [
    ...SYSTEM_VIEWS.map((view) => ({
      id: view.id,
      name: view.name,
      note: view.note,
      search: view.search(now),
    })),
    ...views.map((view) => ({
      id: view.id,
      name: view.name,
      note: undefined,
      search: view.search,
    })),
  ]
  return (
    <nav className="view-strip view-chips" aria-label="Views">
      {chips.map((chip) => {
        const active = sameSearch(chip.search, currentSearch)
        return (
          <Link
            key={chip.id}
            className={active ? 'view-chip active' : 'view-chip'}
            aria-current={active ? 'true' : undefined}
            title={chip.note}
            to={`/search?${chip.search}`}
          >
            {chip.name}
          </Link>
        )
      })}
    </nav>
  )
}

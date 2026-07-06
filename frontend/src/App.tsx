import { useEffect, useMemo, useRef, useState } from 'react'
import type { EngineDto, ProcessInstanceRow } from './api/model'
import { ApiError } from './api/client'
import { useEngines } from './api/useEngines'
import { HeaderStrip } from './components/HeaderStrip'
import { PartialResultsBanner } from './components/PartialResultsBanner'
import { ResultsGrid } from './components/ResultsGrid'
import { SearchRail } from './components/SearchRail'
import { SignIn } from './components/SignIn'
import { formatClock, formatCount } from './lib/format'
import { summarizePartials } from './search/partials'
import { useSearchResults, useSearchUrl } from './search/useSearch'

/**
 * IDE-style split layout: Search rail (left) | Results (center) | Details (right, M3
 * placeholder). Header strip on top. Search state lives in the URL (SPEC §4 Stage 1).
 */
export default function App() {
  const engines = useEngines()
  const { request, submit, paramsKey } = useSearchUrl()
  const results = useSearchResults(request)
  const [railCollapsed, setRailCollapsed] = useState(false)
  const [detailsRow, setDetailsRow] = useState<ProcessInstanceRow | null>(null)
  const collapsedForParams = useRef<string | null>(null)

  // SPEC §4: the rail collapses to chips once a search runs — once per distinct search,
  // so re-expanding it to tweak filters is never fought by a refetch.
  useEffect(() => {
    if (results.isSuccess && collapsedForParams.current !== paramsKey) {
      collapsedForParams.current = paramsKey
      setRailCollapsed(true)
    }
  }, [results.isSuccess, paramsKey])

  const enginesById = useMemo(() => {
    const map = new Map<string, EngineDto>()
    for (const engine of engines.data ?? []) {
      if (engine.id !== undefined) map.set(engine.id, engine)
    }
    return map
  }, [engines.data])

  const authRequired =
    (engines.error instanceof ApiError && engines.error.status === 401) ||
    (results.error instanceof ApiError && results.error.status === 401)

  const searchError =
    results.error !== null && !(results.error instanceof ApiError && results.error.status === 401)
      ? results.error
      : null

  const summary = useMemo(() => summarizePartials(results.data?.perEngine), [results.data])

  return (
    <div className="app">
      <header className="topbar">
        <h1>Flowable Process Inspector</h1>
        <HeaderStrip />
      </header>

      {authRequired && <SignIn />}

      <main className="split">
        <SearchRail
          key={paramsKey}
          engines={engines.data ?? []}
          initial={request}
          response={results.data}
          busy={results.isFetching}
          collapsed={railCollapsed}
          onToggle={() => {
            setRailCollapsed(!railCollapsed)
          }}
          onSubmit={submit}
        />

        <section className="pane pane-results">
          <div className="results-toolbar">
            <span className="snapshot">
              {results.data !== undefined
                ? `${formatCount((results.data.rows ?? []).length)} instances · as of ${formatClock(results.dataUpdatedAt)}`
                : request !== null
                  ? 'Searching…'
                  : 'No search yet'}
            </span>
            <button
              type="button"
              disabled={request === null || results.isFetching}
              onClick={() => {
                void results.refetch()
              }}
            >
              {results.isFetching ? 'Refreshing…' : 'Refresh'}
            </button>
          </div>
          {searchError !== null && (
            <div className="error-banner" role="alert">
              Search failed: {searchError.message}
            </div>
          )}
          <PartialResultsBanner
            summary={summary}
            onRetry={() => {
              void results.refetch()
            }}
          />
          <ResultsGrid
            response={results.data}
            enginesById={enginesById}
            onOpenDetails={setDetailsRow}
          />
        </section>

        {detailsRow !== null && (
          <aside className="pane pane-details">
            <div className="details-header">
              <h2>{detailsRow.compositeId}</h2>
              <button
                type="button"
                aria-label="close details"
                onClick={() => {
                  setDetailsRow(null)
                }}
              >
                ✕
              </button>
            </div>
            <p className="placeholder">
              M3: execution tree · variables (editable) · dead-letter stacktrace · timers/async jobs
              · bpmn-js diagram with active-node highlight.
            </p>
          </aside>
        )}
      </main>
    </div>
  )
}

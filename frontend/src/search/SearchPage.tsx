import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import type { EngineDto, ProcessInstanceRow } from '../api/model'
import { ApiError } from '../api/client'
import { useEngines } from '../api/useEngines'
import { BulkBar } from '../bulk/BulkBar'
import { PartialResultsBanner } from '../components/PartialResultsBanner'
import { ResultsGrid } from '../components/ResultsGrid'
import { SearchRail } from '../components/SearchRail'
import { formatClock, formatCount } from '../lib/format'
import { summarizePartials } from './partials'
import { useSearchResults, useSearchUrl } from './useSearch'

/**
 * Stage 1 (SPEC §4): search rail + results grid. Search state lives in the URL — a shared
 * link replays the exact search. Opening a row leaves this stage for the full-page
 * Stage 2 route (deep-linkable, ticket-pasteable).
 */
export function SearchPage() {
  const navigate = useNavigate()
  const engines = useEngines()
  const { request, submit, paramsKey } = useSearchUrl()
  const results = useSearchResults(request)
  const [railCollapsed, setRailCollapsed] = useState(false)
  const collapsedForParams = useRef<string | null>(null)
  // M5 bulk selection (SPEC §7): checkbox rows flow into the intersection bar.
  const [selectedRows, setSelectedRows] = useState<ProcessInstanceRow[]>([])
  const [deselectSignal, setDeselectSignal] = useState(0)

  // A fresh result set invalidates the old selection — never act on a stale snapshot.
  useEffect(() => {
    setSelectedRows([])
  }, [results.dataUpdatedAt])

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

  const searchError =
    results.error !== null && !(results.error instanceof ApiError && results.error.status === 401)
      ? results.error
      : null

  const summary = useMemo(() => summarizePartials(results.data?.perEngine), [results.data])

  const openDetails = (row: ProcessInstanceRow) => {
    if (row.engineId === undefined || row.processInstanceId === undefined) return
    void navigate(`/inspect/${row.engineId}/${encodeURIComponent(row.processInstanceId)}`)
  }

  return (
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
        <BulkBar
          selected={selectedRows}
          failedEngines={summary.failed}
          truncated={summary.truncated.length > 0}
          onSubmitted={() => {
            setSelectedRows([])
            setDeselectSignal((n) => n + 1)
          }}
        />
        <ResultsGrid
          response={results.data}
          enginesById={enginesById}
          onOpenDetails={openDetails}
          onSelectionRows={setSelectedRows}
          deselectSignal={deselectSignal}
        />
      </section>
    </main>
  )
}

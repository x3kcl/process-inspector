import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import type { EngineDto, ProcessInstanceRow } from '../api/model'
import { ApiError } from '../api/client'
import { useMe } from '../api/me'
import { useEngines } from '../api/useEngines'
import { BulkBar } from '../bulk/BulkBar'
import { PartialResultsBanner } from '../components/PartialResultsBanner'
import { ResultsGrid } from '../components/ResultsGrid'
import { SearchRail } from '../components/SearchRail'
import { formatClock, formatCount } from '../lib/format'
import { RecentSearchList } from '../views/RecentSearchList'
import { ViewChips } from '../views/ViewChips'
import { useRecordRecentSearch } from '../views/useViewStores'
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
  // Usability round 1, Theme B: the same useMe + roleOn path ErrorGroupCard already uses,
  // threaded into BulkBar so its role gate cannot drift from the BFF's real guard.
  const me = useMe()
  const { request, submit, paramsKey } = useSearchUrl()
  const results = useSearchResults(request)
  const recordRecentSearch = useRecordRecentSearch()
  const [railCollapsed, setRailCollapsed] = useState(false)
  const collapsedForParams = useRef<string | null>(null)
  // M5 bulk selection (SPEC §7): checkbox rows flow into the intersection bar.
  const [selectedRows, setSelectedRows] = useState<ProcessInstanceRow[]>([])
  const [deselectSignal, setDeselectSignal] = useState(0)

  // A fresh SEARCH invalidates the old selection — never act on a stale snapshot. Keyed on the
  // search identity (paramsKey), NOT dataUpdatedAt: a "Load more" append must keep the ticked rows;
  // only a new search (or Refresh, handled explicitly below) drops the selection.
  useEffect(() => {
    setSelectedRows([])
  }, [paramsKey])

  // Refresh resets the deep-paging cursor chain (refetch re-runs from page 1 and discards later
  // pages) AND drops any selection made across the now-stale snapshot (docs/KWAY-PAGING.md §4).
  const refresh = () => {
    setSelectedRows([])
    setDeselectSignal((n) => n + 1)
    results.refresh()
  }

  // The oldest row shown — the pre-filled time bound for the depth-wall filter seam ([what]+[next]).
  const lastStartTime = useMemo(() => {
    const rows = results.data?.rows
    return rows !== undefined && rows.length > 0 ? rows[rows.length - 1].startTime : undefined
  }, [results.data])

  // v1.x saved views: a search enters the recents history only once it has EXECUTED
  // successfully — once per distinct URL state, like the rail collapse above.
  const recordedForParams = useRef<string | null>(null)
  useEffect(() => {
    if (results.isSuccess && request !== null && recordedForParams.current !== paramsKey) {
      recordedForParams.current = paramsKey
      recordRecentSearch(paramsKey, request)
    }
  }, [results.isSuccess, request, paramsKey, recordRecentSearch])

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

  // Engine-reported match total across the answering engines — the "~N" context for the
  // filter-scope affordance (v1.x #2); the binding count is server-resolved at execution.
  const matchTotal = useMemo(() => {
    const perEngine = results.data?.perEngine
    if (perEngine === undefined) return undefined
    let total = 0
    for (const result of Object.values(perEngine)) {
      if (result.ok === true) total += result.total ?? 0
    }
    return total
  }, [results.data])

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
        currentSearch={request !== null ? paramsKey : null}
        response={results.data}
        busy={results.isFetching}
        collapsed={railCollapsed}
        onToggle={() => {
          setRailCollapsed(!railCollapsed)
        }}
        onSubmit={submit}
      />

      <section className="pane pane-results">
        <ViewChips currentSearch={paramsKey} />
        <div className="results-toolbar">
          <span className="snapshot">
            {results.data !== undefined
              ? `${formatCount((results.data.rows ?? []).length)} instances · as of ${formatClock(results.dataUpdatedAt)}`
              : request !== null
                ? 'Searching…'
                : 'No search yet'}
          </span>
          <button type="button" disabled={request === null || results.isFetching} onClick={refresh}>
            {results.isFetching ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
        {searchError !== null && (
          <div className="error-banner" role="alert">
            Search failed: {searchError.message}
          </div>
        )}
        <PartialResultsBanner summary={summary} onRetry={refresh} />
        <BulkBar
          selected={selectedRows}
          failedEngines={summary.failed}
          truncated={summary.truncated.length > 0}
          onSubmitted={() => {
            setSelectedRows([])
            setDeselectSignal((n) => n + 1)
          }}
          criteria={results.data !== undefined ? request : null}
          matchTotal={matchTotal}
          visibleCount={(results.data?.rows ?? []).length}
          engines={engines.data ?? []}
          me={me.data}
        />
        {/* Zero state: resume where you left off instead of a blank grid. */}
        {request === null && <RecentSearchList />}
        <ResultsGrid
          response={results.data}
          enginesById={enginesById}
          onOpenDetails={openDetails}
          onSelectionRows={setSelectedRows}
          deselectSignal={deselectSignal}
        />

        {/* v2 deep paging (docs/KWAY-PAGING.md §4): progressive "Load more", surfaced only on an
            overflowing time-ordered result set — never a row-model swap, no numbered pages, no
            infinite scroll. The seam line keeps the snapshot honest; the depth wall is a filter seam. */}
        {results.data !== undefined &&
          (results.hasNextPage || results.data.pagingCoherence === 'snapshot') && (
            <div className="load-more" role="region" aria-label="Load more results">
              {results.data.pagingCoherence === 'snapshot' && (
                <p className="load-more-seam">
                  Loaded more as of {formatClock(results.dataUpdatedAt)} — newer instances won’t
                  appear until Refresh.
                </p>
              )}
              {results.data.depthCapped === true && lastStartTime !== undefined && (
                <p className="load-more-depthwall" role="note">
                  Reached the paging depth on at least one engine.{' '}
                  <button
                    type="button"
                    className="linklike"
                    onClick={() => {
                      if (request !== null) submit({ ...request, startedBefore: lastStartTime })
                    }}
                  >
                    Continue by narrowing to started before {formatClock(Date.parse(lastStartTime))}
                  </button>
                </p>
              )}
              {results.hasNextPage && (
                <button
                  type="button"
                  disabled={results.isFetchingNextPage}
                  onClick={() => {
                    void results.fetchNextPage()
                  }}
                >
                  {results.isFetchingNextPage ? 'Loading…' : 'Load more'}
                </button>
              )}
            </div>
          )}
      </section>
    </main>
  )
}

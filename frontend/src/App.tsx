import { useEffect, useState } from 'react'
import { fetchEngines, search } from './api'
import { SearchPanel } from './components/SearchPanel'
import { ResultsGrid } from './components/ResultsGrid'
import type { EngineInfo, ProcessInstanceRow, SearchRequest, SearchResponse } from './types'

/**
 * IDE-style split layout: Search (left) | Results (center) | Details (right, M3).
 * The details pane is a placeholder until the M3 troubleshooting endpoints land.
 */
export default function App() {
  const [engines, setEngines] = useState<EngineInfo[]>([])
  const [result, setResult] = useState<SearchResponse | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selection, setSelection] = useState<ProcessInstanceRow[]>([])
  const [detailsRow, setDetailsRow] = useState<ProcessInstanceRow | null>(null)

  useEffect(() => {
    fetchEngines().then(setEngines).catch((e) => setError(String(e)))
    const t = setInterval(() => fetchEngines().then(setEngines).catch(() => {}), 30_000)
    return () => clearInterval(t)
  }, [])

  const runSearch = (req: SearchRequest) => {
    setBusy(true)
    setError(null)
    search(req)
      .then(setResult)
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  return (
    <div className="app">
      <header className="topbar">
        <h1>Flowable Process Inspector</h1>
        <div className="engine-badges">
          {engines.map((e) => (
            <span key={e.id} className={`badge ${e.reachable ? 'ok' : 'down'}`} title={e.healthError ?? e.engineVersion ?? ''}>
              <span className="engine-dot" style={{ background: e.accentColor }} />
              {e.id} {e.reachable ? `· v${e.engineVersion ?? '?'}` : '· DOWN'}
            </span>
          ))}
        </div>
      </header>

      {error && <div className="error-banner">{error}</div>}

      <main className="split">
        <aside className="pane pane-search">
          <SearchPanel engines={engines} busy={busy} onSearch={runSearch} />
        </aside>

        <section className="pane pane-results">
          <div className="results-toolbar">
            <span>{result ? `${result.rows.length} instances` : 'Run a search'}</span>
            <span className="bulk-actions">
              {selection.length > 0 && `${selection.length} selected — `}
              <button disabled={selection.length === 0} title="M4">Bulk suspend</button>
              <button disabled={selection.length === 0} title="M4">Bulk delete</button>
            </span>
          </div>
          <ResultsGrid result={result} onSelectionChanged={setSelection} onOpenDetails={setDetailsRow} />
        </section>

        {detailsRow && (
          <aside className="pane pane-details">
            <div className="details-header">
              <h2>{detailsRow.compositeId}</h2>
              <button onClick={() => setDetailsRow(null)}>✕</button>
            </div>
            <p className="placeholder">
              M3: execution tree · variables (editable) · dead-letter stacktrace · timers/async jobs ·
              bpmn-js diagram with active-node highlight.
            </p>
          </aside>
        )}
      </main>
    </div>
  )
}

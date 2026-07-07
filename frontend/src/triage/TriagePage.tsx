import { useMemo } from 'react'
import type { EngineDto } from '../api/model'
import { formatCount, formatDateTime } from '../lib/format'
import { SavedViewsSection } from '../views/SavedViewsSection'
import { ErrorGroupCard } from './ErrorGroupCard'
import { StatusCounts } from './StatusCounts'
import { deriveHonesty, groupCountsAreLowerBound, statusCountsAreLowerBound } from './honesty'
import { useTriage } from './useTriage'

/**
 * Stage 0 — the default route (SPEC §4): "what is broken, how much, where" in zero
 * keystrokes. The M1 engine health strip sits in the shared topbar directly above this
 * page; here live the failure groups, the global status counts and the honesty banners.
 */
export function TriagePage() {
  const triage = useTriage()
  const data = triage.data

  const enginesById = useMemo(() => {
    const map = new Map<string, EngineDto>()
    for (const engine of data?.engines ?? []) {
      if (engine.id !== undefined) map.set(engine.id, engine)
    }
    return map
  }, [data?.engines])

  const honesty = useMemo(() => deriveHonesty(data?.perEngine), [data?.perEngine])

  if (triage.isPending) {
    return <div className="triage zero-state">Aggregating across engines…</div>
  }
  if (triage.isError) {
    return (
      <div className="triage">
        <div className="error-banner" role="alert">
          Triage aggregation failed: {triage.error.message}{' '}
          <button
            type="button"
            onClick={() => {
              void triage.refetch()
            }}
          >
            Retry
          </button>
        </div>
      </div>
    )
  }

  const groups = data?.errorGroups ?? []
  const groupsLowerBound = groupCountsAreLowerBound(honesty)

  return (
    <div className="triage">
      <div className="triage-toolbar">
        <span className="snapshot" title="server-side aggregation stamp (BFF caches ~20s)">
          as of {formatDateTime(data?.asOf)}
        </span>
        <button
          type="button"
          disabled={triage.isFetching}
          title="bypass the BFF triage cache (rate-limited)"
          onClick={() => {
            void triage.refresh()
          }}
        >
          {triage.isFetching ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {honesty.failedEngines.length > 0 && (
        <div className="partial-banner" role="alert">
          {honesty.failedEngines.map((failure) => (
            <span key={failure.engineId}>
              {failure.engineId}: {failure.error} — every count below is a lower bound (this engine
              is excluded)
            </span>
          ))}
        </div>
      )}
      {honesty.truncatedScans.length > 0 && (
        <div className="partial-banner">
          {honesty.truncatedScans.map((scan) => (
            <span key={scan.engineId}>
              {scan.engineId}: failure scan {scan.marker} — error-group counts are lower bounds
            </span>
          ))}
        </div>
      )}

      <StatusCounts counts={data?.statusCounts} lowerBound={statusCountsAreLowerBound(honesty)} />

      <section className="error-groups" aria-label="Failures by error class">
        <h2>
          Failures by error class
          {groups.length > 0 && (
            <span className="group-count"> · {formatCount(groups.length)}</span>
          )}
        </h2>
        {groups.length === 0 ? (
          <div className="zero-state">
            No failure groups — the dead-letter and retrying lanes are clean on every reachable
            engine.
          </div>
        ) : (
          groups.map((group, index) => (
            <ErrorGroupCard
              key={group.signatureHash ?? String(index)}
              group={group}
              enginesById={enginesById}
              lowerBound={groupsLowerBound}
            />
          ))
        )}
      </section>

      {/* Navigation chrome BELOW the failure signal — triage stays zero-keystroke first. */}
      <SavedViewsSection />
    </div>
  )
}

// The Incident Ledger landing (R-BAU-10, INCIDENT-LEDGER.md §8): persisted failure-class
// history + lifecycle, distinct from (and complementary to) Stage 0's recomputed-per-poll
// triage cards. Unpaginated in v1 (bounded by distinct failure classes) — sections are
// derived client-side from the one list fetch, mirroring triage/ErrorGroupSections.tsx's
// active/acknowledged split idiom.
import { useMemo } from 'react'
import type { EngineDto } from '../api/model'
import { useEngines } from '../api/useEngines'
import { formatCount } from '../lib/format'
import { IncidentCard } from './IncidentCard'
import { bucketIncidents } from './sections'
import { useIncidents } from './useIncidents'

export function IncidentsPage() {
  const incidents = useIncidents()
  const engines = useEngines()

  const enginesById = useMemo(() => {
    const map = new Map<string, EngineDto>()
    for (const engine of engines.data ?? []) {
      if (engine.id !== undefined) map.set(engine.id, engine)
    }
    return map
  }, [engines.data])

  const sections = useMemo(() => bucketIncidents(incidents.data ?? []), [incidents.data])

  if (incidents.isPending) {
    return <div className="triage zero-state">Loading the incident ledger…</div>
  }
  if (incidents.isError) {
    return (
      <div className="triage">
        <div className="error-banner" role="alert">
          Could not load the incident ledger: {incidents.error.message}{' '}
          <button
            type="button"
            onClick={() => {
              void incidents.refetch()
            }}
          >
            Retry
          </button>
        </div>
      </div>
    )
  }

  const total = incidents.data.length
  const empty =
    sections.regressed.length +
      sections.open.length +
      sections.quiet.length +
      sections.resolved.length ===
    0

  return (
    <div className="triage">
      <h1>
        Incident Ledger
        {total > 0 && <span className="group-count"> · {formatCount(total)}</span>}
      </h1>
      <p className="strip-note">
        Persisted history for every failure class ever seen (R-BAU-10) — distinct from the Stage-0
        triage cards above, which recompute per poll and forget the moment a class drains. An
        incident here survives across a drain/resolve/regression cycle.
      </p>

      {empty && (
        <div className="zero-state">
          No incidents recorded yet in the current algo generation
          {sections.archived.length > 0
            ? ` (${String(sections.archived.length)} in an archived generation — see below).`
            : '.'}
        </div>
      )}

      {sections.regressed.length > 0 && (
        <section className="incident-section" aria-label="Regressed incidents">
          <h2>Regressed · {formatCount(sections.regressed.length)}</h2>
          {sections.regressed.map((incident) => (
            <IncidentCard
              key={incident.id ?? incident.signatureHash}
              incident={incident}
              enginesById={enginesById}
              variant="regressed"
            />
          ))}
        </section>
      )}

      {sections.open.length > 0 && (
        <section className="incident-section" aria-label="Open incidents">
          <h2>Open · {formatCount(sections.open.length)}</h2>
          {sections.open.map((incident) => (
            <IncidentCard
              key={incident.id ?? incident.signatureHash}
              incident={incident}
              enginesById={enginesById}
              variant="open"
            />
          ))}
        </section>
      )}

      {sections.quiet.length > 0 && (
        <section className="incident-section" aria-label="Quiet incidents">
          <h2>Quiet · {formatCount(sections.quiet.length)}</h2>
          <p className="strip-note">
            Still open, but nothing observed recently — worth a second look before you assume it's
            fixed.
          </p>
          {sections.quiet.map((incident) => (
            <IncidentCard
              key={incident.id ?? incident.signatureHash}
              incident={incident}
              enginesById={enginesById}
              variant="quiet"
            />
          ))}
        </section>
      )}

      {sections.resolved.length > 0 && (
        <details className="ledger-group">
          <summary>
            Resolved <span className="group-count">({formatCount(sections.resolved.length)})</span>
          </summary>
          {sections.resolved.map((incident) => (
            <IncidentCard
              key={incident.id ?? incident.signatureHash}
              incident={incident}
              enginesById={enginesById}
              variant="resolved"
            />
          ))}
        </details>
      )}

      {sections.archived.length > 0 && (
        <details className="ledger-group archived-generations-toggle">
          <summary>
            Archived generations{' '}
            <span className="group-count">({formatCount(sections.archived.length)})</span>
          </summary>
          <p className="strip-note">
            These incidents were fingerprinted under an older signature-normalizer generation
            (R-SEM-03's ALGO_VERSION bump) — kept for history, not counted in the sections above.
          </p>
          {sections.archived.map((incident) => (
            <IncidentCard
              key={incident.id ?? incident.signatureHash}
              incident={incident}
              enginesById={enginesById}
              variant={variantFor(incident)}
            />
          ))}
        </details>
      )}
    </div>
  )
}

function variantFor(incident: {
  state?: string
  quiet?: boolean
}): 'regressed' | 'open' | 'quiet' | 'resolved' {
  if (incident.state === 'REGRESSED') return 'regressed'
  if (incident.state === 'RESOLVED') return 'resolved'
  return incident.quiet === true ? 'quiet' : 'open'
}

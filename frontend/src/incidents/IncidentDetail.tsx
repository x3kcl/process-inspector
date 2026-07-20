// The Incident Ledger's Stage-2-style full-page detail (INCIDENT-LEDGER.md §8) — a route
// (not a drawer): the app's other per-entity deep dives (InspectPage, CasePage) are all
// full-page routes with a deep-linkable URL, and an incident is exactly that kind of
// entity (one persisted lifecycle, worth bookmarking/sharing on its own). 404/out-of-scope
// renders the SAME plain ApiError-message error-note every other detail route uses
// (CasePage/InspectPage's `vitals.isError` idiom) — no special-cased "not found" copy, since
// the BFF deliberately answers the identical 404 for "unknown id" and "exists but out of your
// scope" (existence is not leaked).
import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import type { EngineDto } from '../api/model'
import { useEngines } from '../api/useEngines'
import { useMe } from '../api/me'
import { worstEnvironment } from '../triage/ackState'
import { CopyButton } from '../components/CopyButton'
import { Ts } from '../lib/Ts'
import { talliesLine } from '../ops/outcome'
import { IncidentBreakdownTable } from './IncidentBreakdownTable'
import { IncidentTimeline } from './IncidentTimeline'
import { episodeDurationLabel } from './duration'
import { incidentSearchParams } from './drill'
import { incidentGate } from './gate'
import { countStripLabel } from './liveness'
import { ReopenIncidentModal } from './ReopenIncidentModal'
import { ResolveIncidentModal } from './ResolveIncidentModal'
import { useIncident } from './useIncidents'

export function IncidentDetail() {
  const { id: rawId = '' } = useParams()
  const id = Number.parseInt(rawId, 10)
  const detail = useIncident(id)
  const engines = useEngines()
  const me = useMe()
  const [modal, setModal] = useState<'resolve' | 'reopen' | null>(null)

  const enginesById = useMemo(() => {
    const map = new Map<string, EngineDto>()
    for (const engine of engines.data ?? []) {
      if (engine.id !== undefined) map.set(engine.id, engine)
    }
    return map
  }, [engines.data])

  if (Number.isNaN(id)) {
    return (
      <div className="triage">
        <p className="error-note" role="alert">
          Not a valid incident id.
        </p>
      </div>
    )
  }

  if (detail.isPending) {
    return <p className="muted">Loading incident…</p>
  }
  if (detail.isError) {
    return (
      <div className="triage">
        <p className="error-note" role="alert">
          Could not load this incident: {detail.error.message}
        </p>
      </div>
    )
  }

  const incident = detail.data.incident
  const live = detail.data.live
  const countStrip = countStripLabel(live !== undefined)
  const relatedBulkJobs = detail.data.relatedBulkJobs ?? []
  const episodes = [...(detail.data.episodes ?? [])].sort((a, b) =>
    (b.startedAt ?? '').localeCompare(a.startedAt ?? ''),
  )
  const latestClosed = episodes.find((episode) => episode.endedAt !== undefined)
  const environment = worstEnvironment(
    Object.keys(incident?.countsByEngine ?? {}).map(
      (engineId) => enginesById.get(engineId)?.environment,
    ),
  )
  const gate = incidentGate(me.data)
  const canResolve = incident?.state !== 'RESOLVED'
  const canReopen = incident?.state === 'RESOLVED'

  return (
    <div className="triage">
      <h1>Incident {incident?.exceptionClass !== undefined && `— ${incident.exceptionClass}`}</h1>
      <p className="normalized-message">{incident?.normalizedMessage ?? '(no message)'}</p>

      <dl className="lifecycle-strip">
        <div>
          <dt>State</dt>
          <dd>{incident?.state ?? 'unknown'}</dd>
        </div>
        <div>
          <dt>First seen</dt>
          <dd>
            <Ts iso={incident?.firstSeen} relative />
          </dd>
        </div>
        <div>
          <dt>Last seen</dt>
          <dd>
            <Ts iso={incident?.lastSeen} relative />
          </dd>
        </div>
        <div>
          <dt>Regressions</dt>
          <dd>{String(incident?.regressionCount ?? 0)}</dd>
        </div>
        {incident?.lastRegressedAt !== undefined && (
          <div>
            <dt>Last regressed</dt>
            <dd>
              <Ts iso={incident.lastRegressedAt} relative />
            </dd>
          </div>
        )}
        {latestClosed !== undefined && (
          <div>
            <dt>Last resolved by</dt>
            <dd>
              {latestClosed.resolvedBy ?? '(unknown)'}
              {latestClosed.resolveReason !== undefined && <> — “{latestClosed.resolveReason}”</>}
              {latestClosed.ticketId !== undefined && <> · {latestClosed.ticketId}</>}
            </dd>
          </div>
        )}
      </dl>

      {live?.acknowledgement !== undefined && (
        <p className="ack-meta">
          Acknowledged on the live dashboard by{' '}
          <strong>{live.acknowledgement.acknowledgedBy}</strong>{' '}
          <Ts iso={live.acknowledgement.acknowledgedAt} relative /> — “{live.acknowledgement.reason}
          ”{live.acknowledgement.ticketId != null && <> · {live.acknowledgement.ticketId}</>}{' '}
          (read-only here — manage acknowledgement from the Stage-0 triage card)
        </p>
      )}

      {/* R-NFR-08 honesty (#270) — see liveness.ts for why this is not just "Live total". */}
      <p className="strip-note">
        {countStrip.label} {incident?.lastTruncated === true ? '≥ ' : ''}
        {incident?.lastTotal ?? 0} instances
        {incident?.partial === true && (
          <span className="scope-badge" title="Only engines you can read are reflected">
            {' '}
            in your scope
          </span>
        )}
        {countStrip.drillMayBeEmpty && incident !== undefined && (
          <>
            {' '}
            <Ts iso={incident.lastSeen} relative />
          </>
        )}
        .{' '}
        {incident !== undefined && (
          <Link to={`/search?${incidentSearchParams(incident)}`}>Search these instances</Link>
        )}
        {countStrip.drillMayBeEmpty && (
          <span className="strip-caveat">
            {' '}
            — this class is not failing right now, so the search may return nothing.
          </span>
        )}
      </p>

      <div className="action-slot">
        <button
          type="button"
          disabled={!canResolve || !gate.enabled}
          title={!canResolve ? 'Already resolved' : gate.reason}
          onClick={() => {
            setModal('resolve')
          }}
        >
          Resolve
        </button>
        <button
          type="button"
          disabled={!canReopen || !gate.enabled}
          title={!canReopen ? 'Only a resolved incident can be reopened' : gate.reason}
          onClick={() => {
            setModal('reopen')
          }}
        >
          Reopen
        </button>
      </div>

      <h2>Arrival-rate timeline</h2>
      <IncidentTimeline series={detail.data.series} />

      <h2>Per-engine × definition breakdown</h2>
      <IncidentBreakdownTable
        countsByEngine={live?.countsByEngine ?? incident?.countsByEngine}
        enginesById={enginesById}
        live={live}
        lowerBound={incident?.lastTruncated === true}
      />

      {incident?.sampleRawMessage !== undefined && (
        <>
          <h2>Sample raw message</h2>
          <p className="raw-message-block">{incident.sampleRawMessage}</p>
          <CopyButton text={incident.sampleRawMessage} label="copy raw message" />
        </>
      )}

      <h2>Episodes</h2>
      <ul className="episode-list">
        {episodes.map((episode) => (
          <li
            key={episode.id ?? episode.startedAt}
            className={`episode-row${episode.endedAt === undefined ? ' episode-live' : ''}`}
          >
            <span className="episode-duration">{episodeDurationLabel(episode)}</span> — started{' '}
            <Ts iso={episode.startedAt} relative /> ({episode.startState ?? 'unknown'})
            {episode.endedAt !== undefined && (
              <>
                {' '}
                · ended <Ts iso={episode.endedAt} relative />
                {episode.resolvedBy !== undefined && <> by {episode.resolvedBy}</>}
                {episode.resolveReason !== undefined && <> — “{episode.resolveReason}”</>}
                {episode.ticketId !== undefined && <> · {episode.ticketId}</>}
              </>
            )}
            {' · peak '}
            {episode.peakTotal ?? 0}
          </li>
        ))}
        {episodes.length === 0 && <li className="zero-state">No episodes recorded.</li>}
      </ul>

      {/* S5: the read-only remediation join — recent error-class bulk retries whose submit
          matched THIS signature, in the ops drawer's own vocabulary (state chip + scope chip
          + the R-SEM-11 tallies line). Item-level detail stays on the Operations drawer. */}
      <h2>Recent bulk retries</h2>
      <ul className="related-jobs-list">
        {relatedBulkJobs.map((job) => (
          <li key={job.id ?? job.submittedAt} className="related-job-row">
            <span className={`job-state state-${(job.state ?? '').toLowerCase()}`}>
              {job.state ?? 'unknown'}
            </span>{' '}
            <code>{job.verb ?? ''}</code>
            {typeof job.scopeLabel === 'string' && job.scopeLabel !== '' && (
              <>
                {' '}
                <code className="criteria-chip">{job.scopeLabel}</code>
              </>
            )}{' '}
            <span className="job-meta">
              {job.submittedBy ?? '(unknown)'} · <Ts iso={job.submittedAt} relative />
            </span>{' '}
            <span className="job-tallies">{talliesLine(job)}</span>
          </li>
        ))}
        {relatedBulkJobs.length === 0 && (
          <li className="zero-state">No error-class bulk retries recorded for this incident.</li>
        )}
      </ul>

      {modal === 'resolve' && incident !== undefined && (
        <ResolveIncidentModal
          incident={incident}
          environment={environment}
          onClose={() => {
            setModal(null)
          }}
        />
      )}
      {modal === 'reopen' && incident !== undefined && (
        <ReopenIncidentModal
          incident={incident}
          environment={environment}
          onClose={() => {
            setModal(null)
          }}
        />
      )}
    </div>
  )
}

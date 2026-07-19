// One incident's list-page card (INCIDENT-LEDGER.md §8) — the Incident Ledger's sibling of
// triage/ErrorGroupCard.tsx, but deliberately WITHOUT a per-card sparkline: the list payload
// (`IncidentSummary`) carries no occurrence series (that only comes back on the detail
// fetch), and fetching series per card here would N+1 the API for a page that can list
// hundreds of failure classes. The arrival-rate timeline lives on the detail page only,
// where exactly one incident's series is already being fetched. This is a conscious
// deviation from INCIDENT-LEDGER.md §8's literal "cards with sparklines" phrasing, in favor
// of what the S2 API shape actually supports — flagged for review.
import { Link } from 'react-router'
import type { EngineDto, IncidentSummary } from '../api/model'
import { engineSummary } from './breakdown'
import { formatCount } from '../lib/format'
import { Ts } from '../lib/Ts'

interface Props {
  incident: IncidentSummary
  enginesById: Map<string, EngineDto>
  /** Which section this card renders in — only the visual state token; the incident's own
   *  `state`/`quiet` fields already decided the bucket (see sections.ts). */
  variant: 'regressed' | 'open' | 'quiet' | 'resolved'
}

const STATE_LABEL: Record<Props['variant'], string> = {
  regressed: 'REGRESSED',
  open: 'OPEN',
  quiet: 'QUIET',
  resolved: 'RESOLVED',
}

export function IncidentCard({ incident, enginesById, variant }: Props) {
  const prefix = incident.lastTruncated === true ? '≥ ' : ''
  const { engineCount, definitionCount } = engineSummary(incident.countsByEngine)
  const engineNames = Object.keys(incident.countsByEngine ?? {})
    .sort()
    .map((engineId) => enginesById.get(engineId)?.name ?? engineId)
    .join(', ')

  return (
    <article className={`incident-card incident-${variant}`}>
      <header className="incident-card-head">
        <span className="incident-signature">
          <span className={`incident-state-chip incident-${variant}`}>{STATE_LABEL[variant]}</span>
          {incident.exceptionClass !== undefined && (
            <code className="exception-class" title={incident.exceptionClass}>
              {shortClassName(incident.exceptionClass)}
            </code>
          )}
          <Link
            className="incident-card-link normalized-message"
            to={`/incidents/${String(incident.id ?? '')}`}
          >
            {incident.normalizedMessage ?? '(no message)'}
          </Link>
          {incident.partial === true && (
            <span
              className="scope-badge"
              title="Only the engines you can read are reflected in this count — other engines may also be failing on this signature"
            >
              in your scope
            </span>
          )}
        </span>
        <span
          className="incident-total"
          title={
            incident.lastTruncated === true
              ? 'lower bound — the failure-lane scan behind this count hit its cap'
              : undefined
          }
        >
          {prefix}
          {formatCount(incident.lastTotal ?? 0)} <span className="count-unit">instances</span>
        </span>
      </header>
      <p className="incident-meta-line">
        <span title="First observed">
          first seen <Ts iso={incident.firstSeen} relative />
        </span>
        <span title="Most recently observed">
          last seen <Ts iso={incident.lastSeen} relative />
        </span>
        {engineCount > 0 && (
          <span title={engineNames}>
            {String(engineCount)} engine{engineCount === 1 ? '' : 's'} · {String(definitionCount)}{' '}
            definition{definitionCount === 1 ? '' : 's'}
          </span>
        )}
        {(incident.regressionCount ?? 0) > 0 && (
          <span title="Times this incident has regressed after a resolve">
            regressed {String(incident.regressionCount)}×
          </span>
        )}
      </p>
    </article>
  )
}

/** "org.flowable.common.engine.api.FlowableException" → "FlowableException" (full in title). */
function shortClassName(fqcn: string): string {
  const at = fqcn.lastIndexOf('.')
  return at >= 0 ? fqcn.slice(at + 1) : fqcn
}

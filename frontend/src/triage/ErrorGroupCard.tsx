import { Link } from 'react-router'
import type { EngineDto, ErrorGroup } from '../api/model'
import { EnvBadge } from '../components/EnvBadge'
import { formatCount } from '../lib/format'
import {
  definitionDrillParams,
  engineDrillParams,
  groupDefinitionCounts,
  groupDrillParams,
} from './drill'

interface Props {
  group: ErrorGroup
  enginesById: Map<string, EngineDto>
  /** True when a failed engine or a truncated DLQ scan makes these counts lower bounds. */
  lowerBound: boolean
}

/**
 * One normalized-exception-signature group (the triage centerpiece, SPEC §4 Stage 0).
 * Every count — group total, per engine, per definition version — is its own scope-explicit
 * click target into a pre-filtered Stage 1 search (R-SEM-12). Zero-fill version counts
 * ("v46: 0" beside "v47: 312") stay visible but unlinked: a search cannot scope to a
 * version yet, and a link promising zero rows would lie.
 */
export function ErrorGroupCard({ group, enginesById, lowerBound }: Props) {
  const prefix = lowerBound ? '≥ ' : ''
  const engineEntries = Object.entries(group.countsByEngine ?? {}).sort(
    (a, b) => sumCounts(b[1]) - sumCounts(a[1]) || a[0].localeCompare(b[0]),
  )
  return (
    <article className="error-group">
      <header className="error-group-head">
        <span className="error-signature">
          {group.exceptionClass !== undefined && (
            <code className="exception-class" title={group.exceptionClass}>
              {shortClassName(group.exceptionClass)}
            </code>
          )}
          <span className="normalized-message">{group.normalizedMessage ?? '(no message)'}</span>
        </span>
        <Link
          className="group-total"
          to={`/search?${groupDrillParams(group)}`}
          title="Search FAILED + RETRYING instances on every engine in this group"
        >
          {prefix}
          {formatCount(group.total ?? 0)}
        </Link>
      </header>
      <div className="error-group-lanes">
        <span title="dead-letter jobs (retries exhausted)">
          DLQ {prefix}
          {formatCount(group.deadLetterCount ?? 0)}
        </span>
        <span title="failing jobs with retries left">
          retrying {prefix}
          {formatCount(group.retryingCount ?? 0)}
        </span>
      </div>
      {group.sampleRawMessage !== undefined && (
        <p className="sample-message" title={group.sampleRawMessage}>
          sample: {group.sampleRawMessage}
        </p>
      )}
      <ul className="engine-counts">
        {engineEntries.map(([engineId, byDefVersion]) => (
          <EngineRow
            key={engineId}
            engineId={engineId}
            engine={enginesById.get(engineId)}
            byDefVersion={byDefVersion}
            prefix={prefix}
          />
        ))}
      </ul>
    </article>
  )
}

function EngineRow({
  engineId,
  engine,
  byDefVersion,
  prefix,
}: {
  engineId: string
  engine: EngineDto | undefined
  byDefVersion: Record<string, number>
  prefix: string
}) {
  const definitions = groupDefinitionCounts(byDefVersion)
  return (
    <li className="engine-count-row">
      <Link
        className="engine-count-scope"
        to={`/search?${engineDrillParams(engineId)}`}
        title={`Search all FAILED + RETRYING instances on ${engineId}`}
      >
        <EnvBadge environment={engine?.environment} accentColor={engine?.accentColor} />
        <span className="engine-name">{engine?.name ?? engineId}</span>
      </Link>
      <span className="definition-counts">
        {definitions.map((definition) => (
          <span key={definition.definitionKey} className="definition-count">
            <span className="definition-key">{definition.definitionKey}</span>
            {definition.versions.map((versionCount) => {
              const label = versionCount.version === '' ? 'all' : versionCount.version
              // Zero-filled versions are the regression signal — visible, never a link
              // (the search cannot scope to a version, so the link would over-promise).
              return versionCount.count === 0 ? (
                <span key={label} className="version-count version-zero">
                  {label}: 0
                </span>
              ) : (
                <Link
                  key={label}
                  className="version-count"
                  to={`/search?${definitionDrillParams(engineId, definition.definitionKey)}`}
                  title={
                    `Search FAILED + RETRYING · ${engineId} · ${definition.definitionKey} — ` +
                    'version scope is shown in the grid (no version filter in /api/search yet)'
                  }
                >
                  {label}: {prefix}
                  {formatCount(versionCount.count)}
                </Link>
              )
            })}
          </span>
        ))}
      </span>
    </li>
  )
}

function sumCounts(byDefVersion: Record<string, number>): number {
  return Object.values(byDefVersion).reduce((sum, count) => sum + count, 0)
}

/** "org.flowable.common.engine.api.FlowableException" → "FlowableException" (full in title). */
function shortClassName(fqcn: string): string {
  const at = fqcn.lastIndexOf('.')
  return at >= 0 ? fqcn.slice(at + 1) : fqcn
}

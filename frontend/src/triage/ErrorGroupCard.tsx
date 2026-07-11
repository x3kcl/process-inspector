import { useState } from 'react'
import { Link } from 'react-router'
import { useMe, roleOn } from '../api/me'
import type { EngineDto, ErrorGroup } from '../api/model'
import { actionGate, VERBS } from '../actions/catalog'
import { ActionHint } from '../components/ActionHint'
import { EnvBadge } from '../components/EnvBadge'
import { formatCount } from '../lib/format'
import {
  definitionDrillParams,
  engineDrillParams,
  groupDefinitionCounts,
  groupDrillParams,
  type VersionCount,
} from './drill'
import { RetryGroupModal } from './RetryGroupModal'

/** Newest version rows shown inline per definition; the long tail collapses (Theme E2). */
const VERSIONS_INLINE = 4

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
          title="Open every FAILED + RETRYING instance of this one error class in the grid"
        >
          {prefix}
          {formatCount(group.total ?? 0)}
        </Link>
      </header>
      {/* Usability round 2 (Theme E2): the card total spans every engine/definition/version,
          but each "Retry group" button covers ONE slice — say so, and point at the grid
          drill (now class-scoped) as the one-action path for the whole class. */}
      <p className="strip-note">
        The count opens this one class in the grid — one bulk action there covers all of it. Each
        "Retry group" below covers a single definition + version.
      </p>
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
            group={group}
            engineId={engineId}
            engine={enginesById.get(engineId)}
            byDefVersion={byDefVersion}
            prefix={prefix}
            lowerBound={lowerBound}
          />
        ))}
      </ul>
    </article>
  )
}

function EngineRow({
  group,
  engineId,
  engine,
  byDefVersion,
  prefix,
  lowerBound,
}: {
  group: ErrorGroup
  engineId: string
  engine: EngineDto | undefined
  byDefVersion: Record<string, number>
  prefix: string
  lowerBound: boolean
}) {
  const definitions = groupDefinitionCounts(byDefVersion)
  const me = useMe()
  // Same greying doctrine as the single-target verbs (greyed-never-hidden, tooltip names
  // the gate; the BFF door stays the real check): retry-job's RESPONDER floor — the group
  // retry is an alternate entry to the identical bulk fan-out.
  const gate = actionGate({
    meta: VERBS.retryJob,
    roleHint: roleOn(me.data, engineId),
    engineMode: engine?.mode,
    environment: engine?.environment,
  })
  const [retryScope, setRetryScope] = useState<{
    definitionKey: string
    version: number
    count: number
  } | null>(null)
  return (
    <li className="engine-count-row">
      <Link
        className="engine-count-scope"
        to={`/search?${engineDrillParams(engineId)}`}
        title={`Search all FAILED + RETRYING instances on ${engineId}`}
      >
        <EnvBadge
          environment={engine?.environment}
          accentColor={engine?.accentColor}
          mode={engine?.mode}
          lifecycle={engine?.lifecycle}
        />
        <span className="engine-name">{engine?.name ?? engineId}</span>
      </Link>
      <span className="definition-counts">
        {definitions.map((definition) => {
          const renderVersion = (versionCount: VersionCount) => {
            const label = versionCount.version === '' ? 'all' : versionCount.version
            // Zero-filled versions are the regression signal — visible, never a link
            // (the search cannot scope to a version, so the link would over-promise).
            if (versionCount.count === 0) {
              return (
                <span key={label} className="version-count version-zero">
                  {label}: 0
                </span>
              )
            }
            const version = versionNumberOf(versionCount.version)
            return (
              <span key={label} className="version-count-cell">
                <Link
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
                {version !== null && (
                  <span className="action-slot">
                    <button
                      type="button"
                      className="retry-group-btn"
                      disabled={!gate.enabled}
                      aria-describedby={
                        gate.enabled
                          ? undefined
                          : `retry-group-hint-${engineId}-${definition.definitionKey}-${String(version)}`
                      }
                      title={
                        gate.enabled
                          ? `Retry every currently dead-lettered ${definition.definitionKey} v${String(version)} instance in this error class (resolved server-side)`
                          : gate.detail
                      }
                      onClick={() => {
                        setRetryScope({
                          definitionKey: definition.definitionKey,
                          version,
                          count: versionCount.count,
                        })
                      }}
                    >
                      Retry group
                    </button>
                    {!gate.enabled && gate.reason !== undefined && (
                      <ActionHint
                        id={`retry-group-hint-${engineId}-${definition.definitionKey}-${String(version)}`}
                        text={gate.reason}
                        tone="gate"
                      />
                    )}
                  </span>
                )}
              </span>
            )
          }
          // Usability round 2 (Theme E2): newest versions stay inline (zero-fill included —
          // it is the regression signal); the long tail collapses behind the shared
          // <details> idiom instead of ~30 button rows.
          const overflow = definition.versions.slice(VERSIONS_INLINE)
          return (
            <span key={definition.definitionKey} className="definition-count">
              <span className="definition-key">{definition.definitionKey}</span>
              {definition.versions.slice(0, VERSIONS_INLINE).map(renderVersion)}
              {overflow.length > 0 && (
                <details className="version-overflow">
                  <summary>+{String(overflow.length)} more versions</summary>
                  {overflow.map(renderVersion)}
                </details>
              )}
            </span>
          )
        })}
      </span>
      {retryScope !== null && (
        <RetryGroupModal
          group={group}
          engineId={engineId}
          engine={engine}
          definitionKey={retryScope.definitionKey}
          version={retryScope.version}
          count={retryScope.count}
          lowerBound={lowerBound}
          onClose={() => {
            setRetryScope(null)
          }}
        />
      )}
    </li>
  )
}

/** "v3" → 3; null when the chip has no numeric version (the endpoint needs one). */
function versionNumberOf(version: string): number | null {
  if (!/^v\d+$/.test(version)) return null
  return Number.parseInt(version.slice(1), 10)
}

function sumCounts(byDefVersion: Record<string, number>): number {
  return Object.values(byDefVersion).reduce((sum, count) => sum + count, 0)
}

/** "org.flowable.common.engine.api.FlowableException" → "FlowableException" (full in title). */
function shortClassName(fqcn: string): string {
  const at = fqcn.lastIndexOf('.')
  return at >= 0 ? fqcn.slice(at + 1) : fqcn
}

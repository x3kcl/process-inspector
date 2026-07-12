import { useState } from 'react'
import { Link } from 'react-router'
import { useMe, roleOn } from '../api/me'
import type { EngineDto, ErrorGroup } from '../api/model'
import { actionGate, VERBS } from '../actions/catalog'
import { ActionHint } from '../components/ActionHint'
import { EnvBadge } from '../components/EnvBadge'
import { formatCount } from '../lib/format'
import { Ts } from '../lib/Ts'
import { AcknowledgeGroupModal } from './AcknowledgeGroupModal'
import { ackGate, resurfaceBadge, worstEnvironment } from './ackState'
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
  const me = useMe()
  // ?? undefined: tolerate a null on the wire (the DTO omits absent fields, but a null
  // here must degrade to "unacknowledged", never crash the landing).
  const ack = group.acknowledgement ?? undefined
  const badge = resurfaceBadge(ack)
  // The BFF door mirror (R-BAU-01): OPERATOR on EVERY engine the group is failing on.
  const gate = ackGate(me.data, Object.keys(group.countsByEngine ?? {}))
  const environment = worstEnvironment(
    Object.keys(group.countsByEngine ?? {}).map(
      (engineId) => enginesById.get(engineId)?.environment,
    ),
  )
  const [ackMode, setAckMode] = useState<'acknowledge' | 'unacknowledge' | null>(null)
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
          {badge !== null && (
            <span
              className="grew-badge"
              title="This acknowledged group auto-resurfaced — the failure moved past the acknowledged baseline"
            >
              {badge}
            </span>
          )}
        </span>
        <Link
          className="group-total"
          to={`/search?${groupDrillParams(group)}`}
          title="Open every FAILED + RETRYING instance of this one error class in the grid"
        >
          {prefix}
          {formatCount(group.total ?? 0)} <span className="count-unit">instances</span>
        </Link>
        {/* The mute/ack affordance lives ON the card head (baseline run M7 task 5:
            "the same way monitoring tools silence a known alert") — never Suspend. */}
        <span className="action-slot">
          <button
            type="button"
            className="ack-group-btn"
            disabled={!gate.enabled}
            aria-describedby={
              gate.enabled ? undefined : `ack-gate-hint-${group.signatureHash ?? 'unknown'}`
            }
            title={
              gate.enabled
                ? ack === undefined
                  ? 'Mute this known error class on the landing (engine state untouched); it auto-resurfaces on growth, a new failing version, or expiry'
                  : 'Re-acknowledge at the CURRENT counts — resets the auto-resurface baseline'
                : gate.reason
            }
            onClick={() => {
              setAckMode('acknowledge')
            }}
          >
            {ack === undefined ? 'Acknowledge' : 'Re-acknowledge'}
          </button>
          {!gate.enabled && gate.reason !== undefined && (
            <ActionHint
              id={`ack-gate-hint-${group.signatureHash ?? 'unknown'}`}
              text={gate.reason}
              tone="gate"
            />
          )}
        </span>
      </header>
      {ack !== undefined && (
        <p className="ack-meta">
          Acknowledged by <strong>{ack.acknowledgedBy}</strong>{' '}
          <Ts iso={ack.acknowledgedAt} relative /> at {formatCount(ack.acknowledgedTotal ?? 0)}{' '}
          instances — “{ack.reason}”{ack.ticketId != null && <> · {ack.ticketId}</>}
          {ack.expiresAt != null && (
            <>
              {' '}
              · expires <Ts iso={ack.expiresAt} relative />
            </>
          )}{' '}
          <button
            type="button"
            className="ack-group-btn"
            disabled={!gate.enabled}
            title={
              gate.enabled
                ? 'Remove the acknowledgment — the group returns to the active list'
                : gate.reason
            }
            onClick={() => {
              setAckMode('unacknowledge')
            }}
          >
            Un-acknowledge
          </button>
        </p>
      )}
      {ackMode !== null && (
        <AcknowledgeGroupModal
          group={group}
          mode={ackMode}
          environment={environment}
          onClose={() => {
            setAckMode(null)
          }}
        />
      )}
      {/* Usability round 2 (Theme E2): the card total spans every engine/definition/version,
          but each "Retry group" button covers ONE slice — say so, and point at the grid
          drill (now class-scoped) as the one-action path for the whole class. */}
      <p className="strip-note">
        The count opens this one class in the grid — one bulk action there covers all of it. Each
        "Retry group" below covers a single definition + version.
      </p>
      {/* W2 #7 (T9): the lanes count JOBS, the total counts INSTANCES — two families that
          look comparable but aren't; every count wears its unit token. */}
      {/* S2 (R-SAFE-17): under read scoping a partially-visible group can't honestly split its
          fleet-wide DLQ/retrying counts, so the BFF omits them — render "—" (scope-limited), never
          a misleading "0". The recomputed total + per-engine counts above stay truthful. */}
      <div className="error-group-lanes">
        <span title="dead-letter jobs (retries exhausted)">
          DLQ{' '}
          {group.deadLetterCount === undefined ? (
            <span className="count-scoped" title="limited to your access scope">
              —
            </span>
          ) : (
            <>
              {prefix}
              {formatCount(group.deadLetterCount)} <span className="count-unit">jobs</span>
            </>
          )}
        </span>
        <span title="failing jobs with retries left">
          retrying{' '}
          {group.retryingCount === undefined ? (
            <span className="count-scoped" title="limited to your access scope">
              —
            </span>
          ) : (
            <>
              {prefix}
              {formatCount(group.retryingCount)} <span className="count-unit">jobs</span>
            </>
          )}
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

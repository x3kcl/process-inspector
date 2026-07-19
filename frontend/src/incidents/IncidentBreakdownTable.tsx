// The incident detail's per-engine × definition breakdown (INCIDENT-LEDGER.md §8), with a
// "Retry group" button per definition+version — the SAME triage/RetryGroupModal the Stage-0
// error-group card uses, fed the SAME props its own per-row button feeds it (group,
// engineId, engine, definitionKey, version, count, lowerBound). Deliberately NOT a re-export
// of ErrorGroupCard's private EngineRow: that component's version-overflow <details> + "retry
// all versions" button are more machinery than this table needs, and refactoring an
// already-reviewed triage component from a stacked S4 branch risked more than it saved — a
// second, lighter row renderer composing the identical modal was the safer call. Retry is
// gated on the incident's LIVE Stage-0 join (`detail.live`) being present: RetryGroupModal's
// coordinates (signatureHash/algoVersion) only exist on a real ErrorGroup, never on the
// ledger row alone.
import { useState } from 'react'
import type { EngineDto, ErrorGroup } from '../api/model'
import { roleOn, useMe } from '../api/me'
import { actionGate, VERBS } from '../actions/catalog'
import { ActionHint } from '../components/ActionHint'
import { RetryGroupModal } from '../triage/RetryGroupModal'
import { flattenBreakdown } from './breakdown'

interface Props {
  countsByEngine: Record<string, Record<string, number>> | undefined
  enginesById: Map<string, EngineDto>
  /** The live Stage-0 join — undefined while the class isn't currently failing (RESOLVED, or
   *  quiet past what the join re-derives); retry has no target without it. */
  live: ErrorGroup | undefined
  lowerBound: boolean
}

export function IncidentBreakdownTable({ countsByEngine, enginesById, live, lowerBound }: Props) {
  const rows = flattenBreakdown(countsByEngine)
  if (rows.length === 0) {
    return <p className="strip-note">No per-engine breakdown recorded.</p>
  }
  return (
    <table className="breakdown-table">
      <thead>
        <tr>
          <th>Engine</th>
          <th>Definition</th>
          <th>Version</th>
          <th>Count</th>
          <th>Action</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) =>
          row.definitions.flatMap((definition) =>
            definition.versions.map((versionCount) => (
              <BreakdownRow
                key={`${row.engineId}:${definition.definitionKey}:${versionCount.version}`}
                engineId={row.engineId}
                engine={enginesById.get(row.engineId)}
                definitionKey={definition.definitionKey}
                versionLabel={versionCount.version}
                count={versionCount.count}
                live={live}
                lowerBound={lowerBound}
              />
            )),
          ),
        )}
      </tbody>
    </table>
  )
}

function BreakdownRow({
  engineId,
  engine,
  definitionKey,
  versionLabel,
  count,
  live,
  lowerBound,
}: {
  engineId: string
  engine: EngineDto | undefined
  definitionKey: string
  versionLabel: string
  count: number
  live: ErrorGroup | undefined
  lowerBound: boolean
}) {
  const me = useMe()
  const version = /^v\d+$/.test(versionLabel) ? Number.parseInt(versionLabel.slice(1), 10) : null
  const gate = actionGate({
    meta: VERBS.retryJob,
    roleHint: roleOn(me.data, engineId),
    engineMode: engine?.mode,
    environment: engine?.environment,
  })
  const [confirming, setConfirming] = useState(false)
  const prefix = lowerBound ? '≥ ' : ''
  const retryDisabled = !gate.enabled || live === undefined
  const reason =
    live === undefined ? 'This class is not currently live — nothing to retry' : gate.reason
  const hintId = `incident-retry-hint-${engineId}-${definitionKey}-${versionLabel}`
  return (
    <tr>
      <td>{engine?.name ?? engineId}</td>
      <td>
        <code>{definitionKey}</code>
      </td>
      <td>{versionLabel === '' ? 'all' : versionLabel}</td>
      <td>
        {prefix}
        {count}
      </td>
      <td>
        <span className="action-slot">
          <button
            type="button"
            className="retry-group-btn"
            disabled={retryDisabled}
            aria-describedby={retryDisabled ? hintId : undefined}
            title={
              retryDisabled
                ? reason
                : `Retry every currently dead-lettered ${definitionKey} ${
                    version !== null ? `v${String(version)}` : ''
                  } instance in this incident`
            }
            onClick={() => {
              setConfirming(true)
            }}
          >
            Retry group
          </button>
          {retryDisabled && reason !== undefined && (
            <ActionHint id={hintId} text={reason} tone="gate" />
          )}
        </span>
        {confirming && live !== undefined && (
          <RetryGroupModal
            group={live}
            engineId={engineId}
            engine={engine}
            definitionKey={definitionKey}
            version={version ?? undefined}
            count={count}
            lowerBound={lowerBound}
            onClose={() => {
              setConfirming(false)
            }}
          />
        )}
      </td>
    </tr>
  )
}

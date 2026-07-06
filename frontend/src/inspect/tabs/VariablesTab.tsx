import { useMemo, useState } from 'react'
import type { InstanceVariables, VariableDto } from '../../api/model'
import { fetchInstanceVariable } from '../../api/queries'
import { roleOn, useMe } from '../../api/me'
import { roleAtLeast } from '../../actions/catalog'
import { useEngines } from '../../api/useEngines'
import type { TabProps } from '../InspectPage'
import { useInstanceVariables, useInstanceVitals } from '../useInstanceQueries'
import { VariableLedger } from '../variables/VariableLedger'
import { EditorPanel } from '../variables/editor/EditorPanel'
import { editGateReason } from '../variables/editor/editState'
import type { LedgerRow, VariableEntry } from '../variables/ledger'
import { buildLedger } from '../variables/ledger'

/**
 * The typed variable ledger tab (SPEC §4, R-UXQ-13): the wire DTO maps into the tested
 * presentation model — plain-language type chips, explicit nulls, scope groups. Rows the
 * server truncated (>256 KiB) carry the explicit "load full value" escape hatch; the
 * fetched value then renders through the same typed pipeline, never as a blind dump.
 */
export default function VariablesTab({ engineId, instanceId, selectedActivityId }: TabProps) {
  const query = useInstanceVariables(engineId, instanceId)
  const engines = useEngines()
  const me = useMe()
  const vitals = useInstanceVitals(engineId, instanceId)
  const [fullValues, setFullValues] = useState<Record<string, unknown>>({})
  const [loadingFullName, setLoadingFullName] = useState<string>()
  const [loadError, setLoadError] = useState<string>()
  // §4a: at most one inline edit panel; the row it hangs under is the process-scope name.
  const [editingRow, setEditingRow] = useState<LedgerRow | null>(null)

  const engine = useMemo(
    () => (engines.data ?? []).find((candidate) => candidate.id === engineId),
    [engines.data, engineId],
  )

  const groups = useMemo(
    () => (query.data === undefined ? [] : buildLedger(toEntries(query.data, fullValues))),
    [query.data, fullValues],
  )

  if (query.isPending) return <div className="zero-state">Loading variables…</div>
  if (query.isError) {
    return (
      <div className="error-banner" role="alert">
        Variables unavailable: {query.error.message}
      </div>
    )
  }

  const loadFull = (row: LedgerRow) => {
    setLoadingFullName(row.entry.name)
    setLoadError(undefined)
    fetchInstanceVariable({ engineId, instanceId }, row.entry.name)
      .then((dto: VariableDto) => {
        setFullValues((prev) => ({ ...prev, [row.entry.name]: dto.value ?? null }))
      })
      .catch((error: unknown) => {
        setLoadError(
          `Could not load the full value of “${row.entry.name}”: ${error instanceof Error ? error.message : String(error)}`,
        )
      })
      .finally(() => {
        setLoadingFullName(undefined)
      })
  }

  const instanceEnded = query.data.source === 'HISTORIC'
  const role = roleOn(me.data, engineId)
  const gateFor = (row: LedgerRow) => {
    // §4a Entry gate order: role first (from /api/me — the same resolution the BFF
    // enforces), then the state/type gates. Unknown role stays optimistic.
    if (role !== null && !roleAtLeast(role, 'OPERATOR')) {
      return 'editing values requires the OPERATOR role on this engine'
    }
    return editGateReason({
      engineType: row.entry.engineType,
      scope: row.entry.scope,
      instanceEnded,
      engineMode: engine?.mode,
    })
  }

  return (
    <div className="variables-tab">
      {query.data.source === 'HISTORIC' && (
        <p className="strip-note">Final variable state from history — this instance has ended.</p>
      )}
      {loadError !== undefined && (
        <div className="error-banner" role="alert">
          {loadError}
        </div>
      )}
      <VariableLedger
        groups={groups}
        focusExecutionLabel={selectedActivityId}
        onLoadFull={loadFull}
        loadingFullName={loadingFullName}
        editGateReason={gateFor}
        editingName={editingRow?.entry.name}
        onEditRow={setEditingRow}
        editorNode={
          editingRow !== null ? (
            <EditorPanel
              engineId={engineId}
              instanceId={instanceId}
              entry={editingRow.entry}
              engine={engine}
              vitals={vitals.data}
              onClose={() => {
                setEditingRow(null)
              }}
            />
          ) : undefined
        }
      />
    </div>
  )
}

/** Wire → presentation: the generated DTO becomes the tested VariableEntry model. */
function toEntries(data: InstanceVariables, fullValues: Record<string, unknown>): VariableEntry[] {
  const entries: VariableEntry[] = []
  for (const dto of data.processVariables ?? []) {
    entries.push(toEntry(dto, 'process', undefined, fullValues))
  }
  for (const scope of data.executionScopes ?? []) {
    const label = `${scope.activityId ?? 'execution'} · ${scope.executionId ?? '?'}`
    for (const dto of scope.variables ?? []) {
      entries.push(toEntry(dto, 'local', label, fullValues))
    }
  }
  return entries
}

function toEntry(
  dto: VariableDto,
  scope: 'process' | 'local',
  executionLabel: string | undefined,
  fullValues: Record<string, unknown>,
): VariableEntry {
  const name = dto.name ?? '(unnamed)'
  const fullyLoaded = scope === 'process' && name in fullValues
  return {
    name,
    engineType: dto.type,
    value: fullyLoaded ? fullValues[name] : dto.value,
    scope,
    executionLabel,
    truncated: dto.truncated,
    sizeBytes: dto.sizeBytes,
    fullyLoaded,
  }
}

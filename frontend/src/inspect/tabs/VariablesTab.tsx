import { useMemo, useState } from 'react'
import type { InstanceVariables, VariableDto } from '../../api/model'
import { fetchInstanceVariable } from '../../api/queries'
import { useInstanceVariables } from '../useInstanceQueries'
import { VariableLedger } from '../variables/VariableLedger'
import type { LedgerRow, VariableEntry } from '../variables/ledger'
import { buildLedger } from '../variables/ledger'

interface Props {
  engineId: string
  instanceId: string
}

/**
 * The typed variable ledger tab (SPEC §4, R-UXQ-13): the wire DTO maps into the tested
 * presentation model — plain-language type chips, explicit nulls, scope groups. Rows the
 * server truncated (>256 KiB) carry the explicit "load full value" escape hatch; the
 * fetched value then renders through the same typed pipeline, never as a blind dump.
 */
export default function VariablesTab({ engineId, instanceId }: Props) {
  const query = useInstanceVariables(engineId, instanceId)
  const [fullValues, setFullValues] = useState<Record<string, unknown>>({})
  const [loadingFullName, setLoadingFullName] = useState<string>()
  const [loadError, setLoadError] = useState<string>()

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
      <VariableLedger groups={groups} onLoadFull={loadFull} loadingFullName={loadingFullName} />
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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import type { CustomCellRendererProps } from 'ag-grid-react'
import type { ColDef, RowSelectionOptions, SelectionChangedEvent } from 'ag-grid-community'
import type { EngineDto, ProcessInstanceRow, SearchResponse } from '../api/model'
import { formatCount, formatDateTime } from '../lib/format'
import { summarizePartials, zeroState } from '../search/partials'
import { CopyButton } from './CopyButton'
import { EnvBadge } from './EnvBadge'
import { StatusChip } from './StatusChip'
import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-quartz.css'

interface Props {
  response: SearchResponse | undefined
  /** engineId → engine, for the env band on each row (rows only carry engineId/color). */
  enginesById: Map<string, EngineDto>
  onOpenDetails: (row: ProcessInstanceRow) => void
  /** M5: the selected rows flow up to the bulk action bar (SPEC §7). */
  onSelectionRows?: (rows: ProcessInstanceRow[]) => void
  /** Bump to clear the selection imperatively (after a bulk submit). */
  deselectSignal?: number
}

/**
 * M2c snapshot grid — AG Grid COMMUNITY only (ADR-002): selection count is our own
 * footer, ID copy is an explicit button, filtering lives in the search rail.
 */
export function ResultsGrid({
  response,
  enginesById,
  onOpenDetails,
  onSelectionRows,
  deselectSignal,
}: Props) {
  const gridRef = useRef<AgGridReact<ProcessInstanceRow>>(null)
  const [selectedCount, setSelectedCount] = useState(0)

  useEffect(() => {
    if (deselectSignal !== undefined && deselectSignal > 0) gridRef.current?.api.deselectAll()
  }, [deselectSignal])

  const columns = useMemo<ColDef<ProcessInstanceRow>[]>(
    () => [
      {
        headerName: 'Engine',
        field: 'engineName',
        width: 190,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => {
          if (p.data === undefined) return null
          const engine =
            p.data.engineId !== undefined ? enginesById.get(p.data.engineId) : undefined
          return (
            <span className="engine-cell">
              <EnvBadge
                environment={engine?.environment}
                accentColor={p.data.engineColor ?? engine?.accentColor}
                mode={engine?.mode}
                lifecycle={engine?.lifecycle}
              />
              {p.data.engineName ?? p.data.engineId}
            </span>
          )
        },
      },
      {
        headerName: 'Process ID',
        field: 'processInstanceId',
        width: 170,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => {
          const id = p.data?.processInstanceId
          if (id === undefined) return null
          return (
            <span className="id-cell">
              <code>{id}</code>
              <CopyButton text={id} label="⧉" />
            </span>
          )
        },
      },
      { headerName: 'Business Key', field: 'businessKey', width: 160 },
      {
        headerName: 'Status',
        field: 'status',
        width: 200,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) =>
          p.data === undefined ? null : <StatusChip status={p.data.status} flags={p.data.flags} />,
      },
      {
        headerName: 'Definition',
        colId: 'definition',
        width: 200,
        valueGetter: (p) => {
          if (p.data === undefined) return ''
          const name = p.data.processDefinitionName ?? p.data.processDefinitionKey ?? ''
          const version = p.data.definitionVersion
          return version === undefined ? name : `${name} · v${String(version)}`
        },
      },
      {
        headerName: 'Start Time',
        field: 'startTime',
        width: 180,
        valueFormatter: (p) => formatDateTime(p.value as string | undefined),
        tooltipValueGetter: (p) => p.data?.startTime ?? null,
      },
      {
        headerName: 'Failure Time',
        field: 'failureTime',
        width: 180,
        valueFormatter: (p) => formatDateTime(p.value as string | undefined),
        tooltipValueGetter: (p) => p.data?.failureTime ?? null,
      },
      {
        headerName: 'Current Activity / Error',
        field: 'currentActivityOrError',
        flex: 1,
        minWidth: 220,
        tooltipField: 'currentActivityOrError',
      },
    ],
    [enginesById],
  )

  const rowSelection = useMemo<RowSelectionOptions>(
    () => ({ mode: 'multiRow', enableClickSelection: false }),
    [],
  )

  const onSelectionChanged = useCallback(
    (event: SelectionChangedEvent<ProcessInstanceRow>) => {
      const rows = event.api.getSelectedRows()
      setSelectedCount(rows.length)
      onSelectionRows?.(rows)
    },
    [onSelectionRows],
  )

  const clearSelection = useCallback(() => {
    gridRef.current?.api.deselectAll()
  }, [])

  const rows = response?.rows ?? []

  // SPEC §10a: distinct zero states — never a calm empty grid while an engine is down.
  if (response === undefined) {
    return <div className="zero-state">Run a search to see process instances.</div>
  }
  if (rows.length === 0) {
    const state = zeroState(response)
    const summary = summarizePartials(response.perEngine)
    if (state === 'all-engines-failed') {
      return (
        <div className="zero-state zero-error" role="alert">
          All {formatCount(summary.totalEngines)} engines failed — no data.{' '}
          {summary.failed.map((f) => `${f.engineId}: ${f.error}`).join(' · ')}
        </div>
      )
    }
    if (state === 'zero-under-partial-coverage') {
      const cause =
        summary.failed.length > 0
          ? `${summary.failed.map((f) => f.engineId).join(', ')} unreachable`
          : 'result scans were truncated'
      return (
        <div className="zero-state zero-warn" role="alert">
          0 shown — {cause}; this is NOT a confirmed zero.
        </div>
      )
    }
    return (
      <div className="zero-state">
        No matching instances — confirmed zero across {formatCount(summary.totalEngines)} engine
        {summary.totalEngines === 1 ? '' : 's'}.
      </div>
    )
  }

  return (
    <div className="results-grid">
      <div className="ag-theme-quartz grid-host">
        <AgGridReact<ProcessInstanceRow>
          ref={gridRef}
          rowData={rows}
          columnDefs={columns}
          rowSelection={rowSelection}
          getRowId={(p) => p.data.compositeId ?? p.data.processInstanceId ?? ''}
          onSelectionChanged={onSelectionChanged}
          onRowDoubleClicked={(e) => {
            if (e.data !== undefined) onOpenDetails(e.data)
          }}
          tooltipShowDelay={300}
        />
      </div>
      <div className="grid-footer">
        <span>
          {formatCount(rows.length)} row{rows.length === 1 ? '' : 's'}
          {selectedCount > 0 && ` · ${formatCount(selectedCount)} selected`}
        </span>
        {selectedCount > 0 && (
          <button type="button" onClick={clearSelection}>
            Clear selection
          </button>
        )}
      </div>
    </div>
  )
}

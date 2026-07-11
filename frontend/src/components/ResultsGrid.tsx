import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import type { CustomCellRendererProps } from 'ag-grid-react'
import type {
  CellKeyDownEvent,
  ColDef,
  FullWidthCellKeyDownEvent,
  RowSelectionOptions,
  SelectionChangedEvent,
} from 'ag-grid-community'
import type { EngineDto, ProcessInstanceRow, SearchResponse } from '../api/model'
import { formatCount } from '../lib/format'
import { Ts } from '../lib/Ts'
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
      {
        headerName: 'Business Key',
        field: 'businessKey',
        width: 180,
        // W2 #7 (R-UXQ-12): root-vs-child marker — a businessKey search finds the whole
        // tree, and identically-keyed rows are indistinguishable without it. typeof-guarded
        // (Jackson serializes root rows as superProcessInstanceId: null).
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => {
          const parent = p.data?.superProcessInstanceId
          const child = typeof parent === 'string' && parent !== ''
          return (
            <span className="bk-cell">
              {child && (
                <span
                  className="status-badge child-badge"
                  title={`child instance — started by parent ${parent} (call activity); it shares the tree's business key`}
                >
                  ↳ child
                </span>
              )}
              {p.data?.businessKey}
            </span>
          )
        },
      },
      {
        headerName: 'Status',
        field: 'status',
        width: 200,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) =>
          p.data === undefined ? null : (
            <StatusChip
              status={p.data.status}
              flags={p.data.flags}
              engineId={p.data.engineId}
              instanceId={p.data.processInstanceId}
            />
          ),
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
      // R-UXQ-03: time cells render through <Ts> — absolute + zone token + relative age,
      // with the full UTC ISO on hover/aria (native title; no AG Grid tooltip needed).
      {
        headerName: 'Start Time',
        field: 'startTime',
        width: 230,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => (
          <Ts iso={p.data?.startTime} relative />
        ),
      },
      {
        headerName: 'Failure Time',
        field: 'failureTime',
        width: 230,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => (
          <Ts iso={p.data?.failureTime} relative />
        ),
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

  // R-UXQ-02: ONE row-open handler for both input paths — double-click and Enter on the
  // focused cell (AG Grid Community keyboard hook: onCellKeyDown). Row-open must never
  // be mouse-only.
  const openRow = useCallback(
    (row: ProcessInstanceRow | undefined) => {
      if (row !== undefined) onOpenDetails(row)
    },
    [onOpenDetails],
  )
  const onCellKeyDown = useCallback(
    (
      event: CellKeyDownEvent<ProcessInstanceRow> | FullWidthCellKeyDownEvent<ProcessInstanceRow>,
    ) => {
      const key = event.event
      // defaultPrevented guard (review finding): if a future cell widget/editor claims
      // Enter first, the row-open must not double-fire on the same keystroke.
      if (key instanceof KeyboardEvent && key.key === 'Enter' && !key.defaultPrevented)
        openRow(event.data)
    },
    [openRow],
  )

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
            openRow(e.data)
          }}
          onCellKeyDown={onCellKeyDown}
          tooltipShowDelay={300}
        />
      </div>
      <div className="grid-footer">
        <span>
          {formatCount(rows.length)} row{rows.length === 1 ? '' : 's'}
          {selectedCount > 0 && ` · ${formatCount(selectedCount)} selected`}
        </span>
        {/* R-UXQ-02: the open affordance must be discoverable, next to the (screen-reader)
            "Space to toggle row selection" hint AG Grid already announces. */}
        <span className="grid-keys">
          <kbd>Enter</kbd> opens the focused row · <kbd>Space</kbd> toggles selection · double-click
          opens
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

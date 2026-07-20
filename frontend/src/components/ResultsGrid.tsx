import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router'
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
import { useHiddenColumns } from '../lib/columnVisibility'
import { uncoveredClause, uncoveredEngines } from '../lib/coverage'
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
  // R-UXQ-09 (#104 slice 4/6): ColumnChooser's persisted hide/show set — declarative `hide`
  // recomputed via the memo below, not an imperative api.setColumnsVisible call.
  const hiddenColumns = useHiddenColumns()

  useEffect(() => {
    if (deselectSignal !== undefined && deselectSignal > 0) gridRef.current?.api.deselectAll()
  }, [deselectSignal])

  const columns = useMemo<ColDef<ProcessInstanceRow>[]>(
    () => [
      {
        // U1 (#88): a VISIBLE, keyboard/right-click-able row-open affordance. Double-click and
        // Enter already open the row (R-UXQ-02) but both are invisible; a real <Link> makes it
        // discoverable, focusable, and "open in new tab"-able. Navigates to the same /inspect URL
        // onOpenDetails uses. stopPropagation so the click doesn't also toggle row selection.
        // headerName is non-empty (axe empty-table-header/has-visible-text): an icon-only
        // header cell has no accessible name.
        headerName: 'Open',
        colId: 'open',
        width: 82,
        sortable: false,
        resizable: false,
        suppressMovable: true,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => {
          const engineId = p.data?.engineId
          const id = p.data?.processInstanceId
          if (engineId === undefined || id === undefined) return null
          return (
            <Link
              className="grid-open-link"
              to={`/inspect/${engineId}/${encodeURIComponent(id)}`}
              title="Open this instance's detail (Stage 2)"
              onClick={(event) => {
                event.stopPropagation()
              }}
            >
              Open →
            </Link>
          )
        },
      },
      {
        // R-SAFE-05 (goal-catalog #97 remainder): the point-of-action badge (InstanceActions)
        // and the bulk-bar auto-exclusion already existed — this is the missing "see it before
        // you even open the row" half, so a protected instance never looks like any other row
        // in the results grid. headerName is non-empty for the same accessible-name reason as
        // the Open column (an icon-only header has no accessible name).
        headerName: 'Protected',
        colId: 'protected',
        width: 110,
        sortable: false,
        resizable: false,
        suppressMovable: true,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => {
          if (p.data?.protectedInstance !== true) return null
          return (
            <span
              className="protected-badge"
              title="Protected instance — destructive verbs require ADMIN + a reason"
            >
              🔒 Protected
            </span>
          )
        },
      },
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
        hide: hiddenColumns.has('processInstanceId'),
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
        hide: hiddenColumns.has('businessKey'),
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
              {/* #271: a real space text node, not just CSS margin — the badge and the key
                  must never read (or copy/paste, or get read by a screen reader) as one
                  glued string like "↳ childseed-1784472371". */}
              {child && ' '}
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
              terminationReason={p.data.terminationReason ?? undefined}
              engineId={p.data.engineId}
              instanceId={p.data.processInstanceId}
            />
          ),
      },
      {
        headerName: 'Definition',
        colId: 'definition',
        hide: hiddenColumns.has('definition'),
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
        hide: hiddenColumns.has('startTime'),
        width: 230,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => (
          <Ts iso={p.data?.startTime} relative />
        ),
      },
      {
        headerName: 'Failure Time',
        field: 'failureTime',
        hide: hiddenColumns.has('failureTime'),
        width: 230,
        cellRenderer: (p: CustomCellRendererProps<ProcessInstanceRow>) => (
          <Ts iso={p.data?.failureTime} relative />
        ),
      },
      {
        headerName: 'Current Activity / Error',
        field: 'currentActivityOrError',
        hide: hiddenColumns.has('currentActivityOrError'),
        flex: 1,
        minWidth: 220,
        tooltipField: 'currentActivityOrError',
      },
    ],
    [enginesById, hiddenColumns],
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
    // #236: "confirmed zero across N engines" must name — in the same sentence — the
    // registered engines the search never covered (non-active lifecycle, or outside the
    // selected engine scope), or the zero reads exhaustive when it is not.
    const uncovered = uncoveredEngines(
      [...enginesById.values()],
      Object.keys(response.perEngine ?? {}),
    )
    return (
      <div className="zero-state">
        No matching instances — confirmed zero across {formatCount(summary.totalEngines)} engine
        {summary.totalEngines === 1 ? '' : 's'}
        {uncoveredClause(uncovered, 'searched')}.
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
            "Space to toggle row selection" hint AG Grid already announces. Issue #211: this
            told a keyboard-only user what to do ONCE focus was already on a row, but never how
            to get there — Tab lands you in the grid's header/toolbar, not a row; AG Grid's own
            convention is that ArrowDown from there moves focus onto the first row. */}
        <span className="grid-keys">
          <kbd>↓</kbd> moves into rows · <kbd>Enter</kbd> opens the focused row · <kbd>Space</kbd>{' '}
          toggles selection · double-click opens
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

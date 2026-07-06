import { useState } from 'react'
import { CopyButton } from '../../components/CopyButton'
import { formatDateTime } from '../../lib/format'
import type { LedgerGroup, LedgerRow } from './ledger'
import { EXPAND_BYTE_CAP, serializedBytes } from './ledger'

interface Props {
  groups: LedgerGroup[]
  /** Execution label navigated in from the diagram selection — that group auto-expands. */
  focusExecutionLabel?: string
}

/**
 * The typed variable ledger (SPEC §4, R-UXQ-13): name · plain-language type chip · typed
 * value preview · scope · last-modified. Raw text is the per-row copy escape hatch, never
 * the presentation. Structured bodies render only on explicit expand, capped by size.
 */
export function VariableLedger({ groups, focusExecutionLabel }: Props) {
  if (groups.length === 0) {
    return <div className="zero-state">This instance carries no variables.</div>
  }
  return (
    <div className="variable-ledger">
      {groups.map((group) => (
        <LedgerGroupSection
          key={group.label}
          group={group}
          defaultOpen={
            group.scope === 'process' ||
            (focusExecutionLabel !== undefined && group.label.includes(focusExecutionLabel))
          }
        />
      ))}
    </div>
  )
}

function LedgerGroupSection({ group, defaultOpen }: { group: LedgerGroup; defaultOpen: boolean }) {
  return (
    <details className="ledger-group" open={defaultOpen}>
      <summary>
        {group.label} <span className="group-count">({group.rows.length})</span>
      </summary>
      <table className="ledger-table">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Type</th>
            <th scope="col">Value</th>
            <th scope="col">Scope</th>
            <th scope="col">Last modified</th>
            <th scope="col">
              <span className="visually-hidden">raw copy</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {group.rows.map((row) => (
            <LedgerRowView key={`${group.label}:${row.entry.name}`} row={row} />
          ))}
        </tbody>
      </table>
    </details>
  )
}

function LedgerRowView({ row }: { row: LedgerRow }) {
  const [expanded, setExpanded] = useState(false)
  const bytes = row.preview.expandable ? serializedBytes(row.entry.value) : 0
  const expandBlocked = bytes > EXPAND_BYTE_CAP
  return (
    <>
      <tr>
        <td className="ledger-name">
          <code>{row.entry.name}</code>
          {row.shadowsProcessScope && (
            <span
              className="status-badge"
              title="a step-local value with this name overrides the case-level value inside its execution"
            >
              overrides case-level value
            </span>
          )}
        </td>
        <td>
          <span
            className={`type-chip type-${row.chip.replace(' ', '-').toLowerCase()}`}
            title={chipTitle(row)}
          >
            {row.chip}
          </span>
        </td>
        <td className={`ledger-value${row.preview.muted ? ' value-muted' : ''}`}>
          {row.preview.text}
          {row.preview.expandable && (
            <button
              type="button"
              className="copy-btn"
              disabled={expandBlocked}
              title={
                expandBlocked
                  ? 'value exceeds the preview cap — "load full value" arrives with the detail API'
                  : undefined
              }
              onClick={() => {
                setExpanded(!expanded)
              }}
            >
              {expanded ? 'collapse' : 'expand'}
            </button>
          )}
        </td>
        <td className="ledger-scope">{row.entry.scope === 'process' ? 'case' : 'step-local'}</td>
        <td className="ledger-modified">{formatDateTime(row.entry.lastModified)}</td>
        <td>
          <CopyButton text={rawCopyText(row)} label="copy raw" />
        </td>
      </tr>
      {expanded && !expandBlocked && (
        <tr className="ledger-expansion">
          <td colSpan={6}>
            {/* Read-only body, rendered ONLY on explicit expand (SPEC §4 lazy rule). */}
            <pre className="value-body">{rawCopyText(row)}</pre>
          </td>
        </tr>
      )}
    </>
  )
}

function chipTitle(row: LedgerRow): string {
  const engineTerm =
    row.entry.engineType !== undefined
      ? `engine type: ${row.entry.engineType}`
      : 'engine type not declared'
  return row.chip === 'Java object'
    ? `${engineTerm} — REST cannot round-trip serializable Java objects; copy the value for the owning dev team instead`
    : engineTerm
}

/** The escape hatch (R-L3-03): exact raw text per row — never the default presentation. */
function rawCopyText(row: LedgerRow): string {
  const value = row.entry.value
  if (typeof value === 'string') return value
  if (value === undefined) return 'null'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    // Circular or otherwise unserializable — say so instead of "[object Object]".
    return '(value could not be serialized)'
  }
}

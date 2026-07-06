// §4a form mode: typed widgets — the operator edits a VALUE, never a payload. Number
// gets the live parsed echo; boolean is a True/False segmented control; date shows the
// dual wall-clock + exact-UTC readout; text makes whitespace visible; JSON renders the
// leaf tree where the dominant "flip one flag" task never shows a brace.
import { useMemo } from 'react'
import { Segmented } from '../../../components/Segmented'
import { formatDateTime } from '../../../lib/format'
import { short } from './diff'
import { formatPath, parseDateInput, parseNumberInput, textEcho } from './editState'
import type { LeafPath } from './editState'

/* ------------------------------ scalar widgets ------------------------------ */

export function TextWidget({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="edit-widget">
      <textarea
        className="edit-text"
        value={value}
        rows={value.includes('\n') ? 4 : 1}
        autoCorrect="off"
        autoCapitalize="off"
        spellCheck={false}
        aria-label="new text value"
        onChange={(event) => {
          onChange(event.target.value)
        }}
      />
      <p className="edit-echo">{textEcho(value)}</p>
    </div>
  )
}

export function NumberWidget({
  raw,
  subtype,
  onChange,
}: {
  raw: string
  subtype: 'integer' | 'long' | 'short' | 'double'
  onChange: (raw: string) => void
}) {
  const parsed = parseNumberInput(raw, subtype)
  return (
    <div className="edit-widget">
      <input
        type="text"
        inputMode="decimal"
        className="edit-number"
        value={raw}
        aria-label="new numeric value"
        onChange={(event) => {
          onChange(event.target.value)
        }}
      />
      {parsed.ok ? (
        <p className="edit-echo">{parsed.echo}</p>
      ) : (
        <p className="edit-error" role="alert">
          {parsed.error}
        </p>
      )}
    </div>
  )
}

export function BooleanWidget({
  value,
  onChange,
}: {
  value: boolean | undefined
  onChange: (v: boolean) => void
}) {
  return (
    <div className="edit-widget">
      {/* Segmented, never a toggle (§4a): nothing applies before confirm. */}
      <Segmented<'true' | 'false'>
        ariaLabel="new boolean value"
        options={[
          { value: 'true', label: 'True' },
          { value: 'false', label: 'False' },
        ]}
        value={value === undefined ? undefined : value ? 'true' : 'false'}
        onChange={(next) => {
          onChange(next === 'true')
        }}
      />
    </div>
  )
}

export function DateWidget({ raw, onChange }: { raw: string; onChange: (raw: string) => void }) {
  const parsed = parseDateInput(raw)
  return (
    <div className="edit-widget">
      <input
        type="text"
        className="edit-date"
        value={raw}
        placeholder="2026-07-06T14:30:00+02:00"
        aria-label="new date-time value with explicit offset"
        onChange={(event) => {
          onChange(event.target.value)
        }}
      />
      {parsed.ok ? (
        <p className="edit-echo">
          {/* Timezone-honesty dual readout (§4a): operator wall-clock AND exact UTC. */}
          your time: {formatDateTime(String(parsed.value))} · stored: {String(parsed.value)}
        </p>
      ) : (
        <p className="edit-error" role="alert">
          {parsed.error}
        </p>
      )}
    </div>
  )
}

/* ------------------------------ JSON leaf tree ------------------------------ */

interface LeafTreeProps {
  /** The staged document (original + applied leaf edits). */
  value: unknown
  /** Paths already staged this session — badged so the operator sees what moved. */
  editedPaths: Set<string>
  onLeafChange: (path: LeafPath, value: unknown) => void
}

/**
 * The same tree as the read view, with editable leaves (§4a). Structural changes
 * (add/remove keys, array surgery) deliberately require source mode.
 */
export function LeafTree({ value, editedPaths, onLeafChange }: LeafTreeProps) {
  if (value === null || typeof value !== 'object') {
    return (
      <LeafRow
        label="(value)"
        path={[]}
        value={value}
        edited={editedPaths.has('')}
        onLeafChange={onLeafChange}
      />
    )
  }
  return (
    <div className="leaf-tree">
      <Branch value={value} path={[]} editedPaths={editedPaths} onLeafChange={onLeafChange} />
      <p className="edit-echo">
        Leaf values are editable here; adding/removing fields or array items needs Source mode.
      </p>
    </div>
  )
}

function Branch({
  value,
  path,
  editedPaths,
  onLeafChange,
}: {
  value: object
  path: LeafPath
  editedPaths: Set<string>
  onLeafChange: (path: LeafPath, value: unknown) => void
}) {
  const entries: [string | number, unknown][] = Array.isArray(value)
    ? value.map((item, index) => [index, item] as [number, unknown])
    : Object.entries(value)
  return (
    <ul className="leaf-branch">
      {entries.map(([key, child]) => {
        const childPath = [...path, key]
        const pathLabel = formatPath(childPath)
        if (child !== null && typeof child === 'object') {
          return (
            <li key={pathLabel}>
              <details open={path.length === 0}>
                <summary>
                  <code>{String(key)}</code>{' '}
                  <span className="value-muted">
                    {Array.isArray(child)
                      ? `array · ${String(child.length)} items`
                      : `object · ${String(Object.keys(child).length)} fields`}
                  </span>
                </summary>
                <Branch
                  value={child}
                  path={childPath}
                  editedPaths={editedPaths}
                  onLeafChange={onLeafChange}
                />
              </details>
            </li>
          )
        }
        return (
          <li key={pathLabel}>
            <LeafRow
              label={String(key)}
              path={childPath}
              value={child}
              edited={editedPaths.has(pathLabel)}
              onLeafChange={onLeafChange}
            />
          </li>
        )
      })}
    </ul>
  )
}

function LeafRow({
  label,
  path,
  value,
  edited,
  onLeafChange,
}: {
  label: string
  path: LeafPath
  value: unknown
  edited: boolean
  onLeafChange: (path: LeafPath, value: unknown) => void
}) {
  return (
    <div className={`leaf-row${edited ? ' leaf-edited' : ''}`}>
      <code className="leaf-name">{label}</code>
      <LeafInput path={path} value={value} onLeafChange={onLeafChange} />
      {edited && <span className="status-badge">staged</span>}
    </div>
  )
}

/** Scalar widget per JSON type — a null leaf shows its state and offers text entry. */
function LeafInput({
  path,
  value,
  onLeafChange,
}: {
  path: LeafPath
  value: unknown
  onLeafChange: (path: LeafPath, value: unknown) => void
}) {
  if (typeof value === 'boolean') {
    return (
      <Segmented<'true' | 'false'>
        ariaLabel={`${formatPath(path)} value`}
        options={[
          { value: 'true', label: 'True' },
          { value: 'false', label: 'False' },
        ]}
        value={value ? 'true' : 'false'}
        onChange={(next) => {
          onLeafChange(path, next === 'true')
        }}
      />
    )
  }
  if (typeof value === 'number') {
    return (
      <NumberLeaf
        value={value}
        onCommit={(next) => {
          onLeafChange(path, next)
        }}
      />
    )
  }
  if (typeof value === 'string') {
    return (
      <input
        type="text"
        className="leaf-input"
        value={value}
        aria-label={`${formatPath(path)} value`}
        onChange={(event) => {
          onLeafChange(path, event.target.value)
        }}
      />
    )
  }
  // null leaf: explicit, editable to a string only — type surgery belongs to source mode.
  return (
    <span className="value-muted">
      (no value / null) — set a value in Source mode to change its type
    </span>
  )
}

/** Numeric leaves keep a local raw string so "12." mid-typing does not corrupt the doc. */
function NumberLeaf({ value, onCommit }: { value: number; onCommit: (next: number) => void }) {
  // The staged doc is the source of truth; the input re-derives from it per keystroke
  // that parses, and simply shows the raw text while it does not.
  const raw = useMemo(() => String(value), [value])
  return (
    <input
      type="text"
      inputMode="decimal"
      className="leaf-input leaf-number"
      defaultValue={raw}
      aria-label="numeric leaf value"
      title={`current: ${short(value)}`}
      onChange={(event) => {
        const next = Number(event.target.value)
        if (event.target.value.trim() !== '' && Number.isFinite(next)) onCommit(next)
      }}
    />
  )
}

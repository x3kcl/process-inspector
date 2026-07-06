import { useState } from 'react'

/** Children rendered per reveal step — keeps a 10k-item array from locking the thread. */
const CHILD_PAGE = 100

/**
 * The lazy, read-only tree for structured variable values (SPEC §4): branch children
 * render ONLY once their node is expanded, and large collections reveal in pages — the
 * DOM stays bounded no matter the payload. Values render per the R-UXQ-13 language
 * (explicit nulls, booleans as words) — raw JSON stays behind "copy raw".
 */
export function JsonTree({ value }: { value: unknown }) {
  return (
    <div className="json-tree">
      <JsonNode name={undefined} value={value} defaultOpen />
    </div>
  )
}

function JsonNode({
  name,
  value,
  defaultOpen = false,
}: {
  name?: string
  value: unknown
  defaultOpen?: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  const [visible, setVisible] = useState(CHILD_PAGE)

  const entries = childEntries(value)
  if (entries === null) {
    return (
      <span className="json-leaf">
        {name !== undefined && <code className="json-key">{name}</code>}
        <LeafValue value={value} />
      </span>
    )
  }

  const summary = Array.isArray(value)
    ? `array · ${String(entries.length)} item${entries.length === 1 ? '' : 's'}`
    : `object · ${String(entries.length)} field${entries.length === 1 ? '' : 's'}`
  return (
    <div className="json-branch">
      <button
        type="button"
        className="json-toggle"
        aria-expanded={open}
        onClick={() => {
          setOpen(!open)
        }}
      >
        <span aria-hidden="true">{open ? '▾' : '▸'}</span>
        {name !== undefined && <code className="json-key">{name}</code>}
        <span className="json-summary">{summary}</span>
      </button>
      {open && (
        <ul className="json-children">
          {entries.slice(0, visible).map(([key, child]) => (
            <li key={key}>
              <JsonNode name={key} value={child} />
            </li>
          ))}
          {entries.length > visible && (
            <li>
              <button
                type="button"
                className="copy-btn"
                onClick={() => {
                  setVisible(visible + CHILD_PAGE)
                }}
              >
                show {String(Math.min(CHILD_PAGE, entries.length - visible))} more (
                {String(entries.length - visible)} hidden)
              </button>
            </li>
          )}
        </ul>
      )}
    </div>
  )
}

function childEntries(value: unknown): [string, unknown][] | null {
  if (Array.isArray(value)) return value.map((child, index) => [`[${String(index)}]`, child])
  if (value !== null && typeof value === 'object') return Object.entries(value)
  return null
}

/** Leaf rendering follows the ledger language: never a bare "null", never a toggle glyph. */
function LeafValue({ value }: { value: unknown }) {
  if (value === null || value === undefined) {
    return <span className="value-muted">(no value / null)</span>
  }
  if (typeof value === 'boolean') return <span>{value ? 'Yes (true)' : 'No (false)'}</span>
  if (typeof value === 'number') return <span>{String(value)}</span>
  if (typeof value === 'string') {
    if (value === '') return <span className="value-muted">(empty text)</span>
    return <span className="json-string">{value}</span>
  }
  // bigint/symbol/function — cannot appear in parsed JSON, but say something legible.
  return <span className="value-muted">({typeof value})</span>
}

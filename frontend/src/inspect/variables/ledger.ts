// The typed variable ledger (SPEC §4, R-UXQ-13): NEVER a raw JSON dump. Every rendering
// decision — plain-language type chips, explicit "(no value / null)", booleans as words,
// structured values as summaries — lives here as pure, tested derivations. The wire
// adapter (once the BFF exposes the variables endpoint) maps generated DTOs into
// VariableEntry; components consume only the derived rows.
import { formatDateTime } from '../../lib/format'

/** Presentation view-model over the generated VariableDto (adapter in VariablesTab). */
export interface VariableEntry {
  name: string
  /** The engine's declared type (string/long/integer/short/double/boolean/date/json/serializable/null…). */
  engineType?: string
  value: unknown
  scope: 'process' | 'local'
  /** For execution-local variables: the owning node, e.g. "Validate line item · instance 3 of 12". */
  executionLabel?: string
  /** For execution-local variables: the owning execution id — the scoped read/edit target. */
  executionId?: string
  /** ISO timestamp of the last write, when the engine surfaces it. */
  lastModified?: string
  /** Server-truncated: the value exceeded the 256 KiB preview cap and shipped as null. */
  truncated?: boolean
  /** Serialized size the server measured — shown next to the truncation notice. */
  sizeBytes?: number
  /** The user pulled the full value through the escape hatch — expansion is unblocked. */
  fullyLoaded?: boolean
}

/** Plain-language type chips (SPEC §4) — the engine term belongs in the glossary tooltip. */
export type TypeChip =
  'text' | 'number' | 'yes-no' | 'date' | 'structured' | 'empty' | 'Java object'

const NUMBER_TYPES = new Set(['long', 'integer', 'short', 'double', 'number'])

export function typeChip(entry: VariableEntry): TypeChip {
  const engineType = entry.engineType?.toLowerCase()
  // A truncated value shipped as null but IS NOT empty — chip from the declared type.
  if (entry.truncated === true && entry.fullyLoaded !== true) {
    if (engineType === 'string') return 'text'
    if (engineType === 'serializable') return 'Java object'
    return 'structured'
  }
  if (entry.value === null || entry.value === undefined || engineType === 'null') return 'empty'
  if (engineType === 'boolean' || typeof entry.value === 'boolean') return 'yes-no'
  if (engineType !== undefined && NUMBER_TYPES.has(engineType)) return 'number'
  if (engineType === 'date') return 'date'
  if (engineType === 'json') return 'structured'
  if (engineType === 'serializable') return 'Java object'
  if (engineType === 'string') return 'text'
  // No declared type: fall back to the value's own shape.
  if (typeof entry.value === 'number') return 'number'
  if (typeof entry.value === 'object') return 'structured'
  return 'text'
}

/** The engine term for the glossary tooltip on the chip. */
export function chipTooltip(entry: VariableEntry): string {
  return entry.engineType !== undefined
    ? `engine type: ${entry.engineType}`
    : 'engine type not declared'
}

export interface ValuePreview {
  text: string
  /** Meta text ("(no value / null)", summaries) renders demoted, real values do not. */
  muted: boolean
  /** Structured values can expand into the lazy read-only tree. */
  expandable: boolean
}

const PREVIEW_CHAR_CAP = 140
/** Above this serialized size the expander defers to "load full value" (SPEC §4 size cap). */
export const EXPAND_BYTE_CAP = 256 * 1024

export function valuePreview(entry: VariableEntry): ValuePreview {
  // SPEC §4 size safeguard: over-cap values never ship in the ledger — the row states
  // the fact + size; "load full value" is the explicit escape hatch next to it.
  if (entry.truncated === true && entry.fullyLoaded !== true) {
    const size = entry.sizeBytes !== undefined ? ` (${formatBytes(entry.sizeBytes)})` : ''
    return {
      text: `value exceeds the 256 KiB preview cap${size}`,
      muted: true,
      expandable: false,
    }
  }
  const chip = typeChip(entry)
  switch (chip) {
    case 'empty':
      // Explicit, never blank — a null must be tellable-apart from an empty string.
      return { text: '(no value / null)', muted: true, expandable: false }
    case 'yes-no': {
      const truthy = entry.value === true || entry.value === 'true'
      // Words, never a toggle glyph (SPEC §4a: toggles read as immediate-effect).
      return { text: truthy ? 'Yes (true)' : 'No (false)', muted: false, expandable: false }
    }
    case 'date': {
      const iso = typeof entry.value === 'string' ? entry.value : String(entry.value)
      return { text: formatDateTime(iso), muted: false, expandable: false }
    }
    case 'structured':
      return { text: structuredSummary(entry.value), muted: true, expandable: true }
    case 'Java object':
      // Intentionally locked, never broken: REST cannot round-trip serializables.
      return { text: 'serialized Java object — read-only', muted: true, expandable: false }
    case 'number':
      return { text: String(entry.value), muted: false, expandable: false }
    case 'text': {
      const text = typeof entry.value === 'string' ? entry.value : String(entry.value)
      if (text === '') return { text: '(empty text)', muted: true, expandable: false }
      return text.length > PREVIEW_CHAR_CAP
        ? {
            text: `${text.slice(0, PREVIEW_CHAR_CAP)}… (${String(text.length)} chars)`,
            muted: false,
            expandable: true,
          }
        : { text, muted: false, expandable: false }
    }
  }
}

/** "object · 14 fields · 2.1 KiB" — the summary IS the presentation; the tree is opt-in. */
export function structuredSummary(value: unknown): string {
  const bytes = serializedBytes(value)
  if (Array.isArray(value)) {
    return `array · ${String(value.length)} items · ${formatBytes(bytes)}`
  }
  if (value !== null && typeof value === 'object') {
    return `object · ${String(Object.keys(value).length)} fields · ${formatBytes(bytes)}`
  }
  return `structured · ${formatBytes(bytes)}`
}

export function serializedBytes(value: unknown): number {
  if (value === undefined) return 0
  try {
    return new TextEncoder().encode(JSON.stringify(value)).length
  } catch {
    return 0
  }
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${String(bytes)} B`
  const kib = bytes / 1024
  if (kib < 1024) return `${(Math.round(kib * 10) / 10).toString()} KiB`
  return `${(Math.round((kib / 1024) * 10) / 10).toString()} MiB`
}

export interface LedgerRow {
  entry: VariableEntry
  chip: TypeChip
  preview: ValuePreview
  /** A local variable shadowing a process-scope name — badged "overrides case-level value". */
  shadowsProcessScope: boolean
}

export interface LedgerGroup {
  /** "Case data (process scope)" or "Step-local: <execution label>". */
  label: string
  scope: 'process' | 'local'
  rows: LedgerRow[]
}

export const PROCESS_GROUP_LABEL = 'Case data (process scope)'

/**
 * Grouping per SPEC §4: process scope first (open by default), then one group per
 * execution node, alphabetical; rows alphabetical within their group.
 */
export function buildLedger(entries: VariableEntry[]): LedgerGroup[] {
  const processNames = new Set(
    entries.filter((entry) => entry.scope === 'process').map((entry) => entry.name),
  )
  const toRow = (entry: VariableEntry): LedgerRow => ({
    entry,
    chip: typeChip(entry),
    preview: valuePreview(entry),
    shadowsProcessScope: entry.scope === 'local' && processNames.has(entry.name),
  })
  const byName = (a: LedgerRow, b: LedgerRow) => a.entry.name.localeCompare(b.entry.name)

  const processRows = entries
    .filter((entry) => entry.scope === 'process')
    .map(toRow)
    .sort(byName)

  const localGroups = new Map<string, LedgerRow[]>()
  for (const entry of entries) {
    if (entry.scope !== 'local') continue
    const label = entry.executionLabel ?? 'unnamed execution'
    const rows = localGroups.get(label) ?? []
    rows.push(toRow(entry))
    localGroups.set(label, rows)
  }

  const groups: LedgerGroup[] = []
  if (processRows.length > 0) {
    groups.push({ label: PROCESS_GROUP_LABEL, scope: 'process', rows: processRows })
  }
  for (const label of [...localGroups.keys()].sort()) {
    const rows = localGroups.get(label) ?? []
    rows.sort(byName)
    groups.push({ label: `Step-local: ${label}`, scope: 'local', rows })
  }
  return groups
}

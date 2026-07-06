// The §4a variable editor's pure core: typed-widget selection, per-subtype validation
// with the live parsed echo ("the echo IS the contract"), the type lock, explicit
// empty-vs-null clearing, JSON leaf staging, and the source-mode gates (parse + type
// check + byte-size pre-flight). Everything here is presentation-free and tested;
// components stay thin.
import { serializedBytes } from '../ledger'

/** Engine types the editor can write. Serializables are locked out entirely (§4a). */
export type EditableType = 'string' | 'integer' | 'long' | 'short' | 'double' | 'boolean' | 'date' | 'json'

export const EDITABLE_TYPES: readonly EditableType[] = [
  'string',
  'integer',
  'long',
  'short',
  'double',
  'boolean',
  'date',
  'json',
]

export function asEditableType(engineType: string | undefined): EditableType | null {
  const lower = engineType?.toLowerCase()
  return (EDITABLE_TYPES as readonly string[]).includes(lower ?? '')
    ? (lower as EditableType)
    : null
}

/** Why a row's pencil is greyed (greyed-with-reason, never hover-hidden — §4a Entry). */
export function editGateReason(input: {
  engineType?: string
  scope: 'process' | 'local'
  instanceEnded: boolean
  engineMode?: string
}): string | null {
  if (input.engineMode !== undefined && input.engineMode.toUpperCase() === 'READ_ONLY') {
    return 'this engine is registered read-only'
  }
  if (input.instanceEnded) {
    return 'the instance has ended — historic variables are a record, not state'
  }
  if (input.scope === 'local') {
    return 'step-local edits are not supported yet — edit the case-level value or use the engine directly'
  }
  if (input.engineType?.toLowerCase() === 'serializable') {
    return 'REST cannot round-trip serializable Java objects — copy the value for the owning dev team instead'
  }
  if (asEditableType(input.engineType) === null && input.engineType !== undefined) {
    return `type "${input.engineType}" is not editable over REST`
  }
  return null
}

/* ------------------------------- scalar parsing ------------------------------- */

export type ParseResult =
  | { ok: true; value: unknown; echo: string }
  | { ok: false; error: string }

const INT_RANGES: Record<'integer' | 'short', { min: number; max: number }> = {
  integer: { min: -2147483648, max: 2147483647 },
  short: { min: -32768, max: 32767 },
}

/** Number parsing with per-subtype range validation and the live parsed echo (§4a). */
export function parseNumberInput(raw: string, subtype: 'integer' | 'long' | 'short' | 'double'): ParseResult {
  const trimmed = raw.trim()
  if (trimmed === '') return { ok: false, error: 'enter a number (or use Clear for no value)' }
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(trimmed)) {
    return { ok: false, error: `"${trimmed}" is not a number` }
  }
  const parsed = Number(trimmed)
  if (!Number.isFinite(parsed)) return { ok: false, error: `"${trimmed}" is not a finite number` }
  if (subtype === 'double') {
    return { ok: true, value: parsed, echo: `will be stored as double ${String(parsed)}` }
  }
  if (!Number.isInteger(parsed)) {
    return { ok: false, error: `${subtype} cannot hold the fraction in ${trimmed}` }
  }
  if (subtype === 'long') {
    if (!Number.isSafeInteger(parsed)) {
      return {
        ok: false,
        error: 'beyond the safe integer range this editor can represent exactly (±2^53)',
      }
    }
    return { ok: true, value: parsed, echo: `will be stored as long ${String(parsed)}` }
  }
  const range = INT_RANGES[subtype]
  if (parsed < range.min || parsed > range.max) {
    return {
      ok: false,
      error: `${subtype} range is ${String(range.min)} to ${String(range.max)}`,
    }
  }
  return { ok: true, value: parsed, echo: `will be stored as ${subtype} ${String(parsed)}` }
}

/**
 * Date input must carry an explicit offset (§4a timezone honesty): "2026-07-06T14:30"
 * is rejected; "…Z" or "…+02:00" is normalized to the exact UTC ISO-8601 string sent.
 */
export function parseDateInput(raw: string): ParseResult {
  const trimmed = raw.trim()
  if (trimmed === '') return { ok: false, error: 'enter a date-time (or use Clear for no value)' }
  if (!/([zZ]|[+-]\d{2}:\d{2})$/.test(trimmed)) {
    return {
      ok: false,
      error: 'offset required — end with Z or ±hh:mm so the stored instant is unambiguous',
    }
  }
  const parsed = new Date(trimmed)
  if (Number.isNaN(parsed.getTime())) return { ok: false, error: `"${trimmed}" is not a valid date-time` }
  const utc = parsed.toISOString()
  return { ok: true, value: utc, echo: `will be stored as ${utc} (UTC)` }
}

/** Text keeps leading/trailing whitespace visible; the echo names it explicitly. */
export function textEcho(raw: string): string {
  const leading = raw.length - raw.trimStart().length
  const trailing = raw.length - raw.trimEnd().length
  const notes: string[] = []
  if (leading > 0) notes.push(`${String(leading)} leading space${leading === 1 ? '' : 's'}`)
  if (trailing > 0) notes.push(`${String(trailing)} trailing space${trailing === 1 ? '' : 's'}`)
  const base = `will be stored as text (${String(raw.length)} chars)`
  return notes.length > 0 ? `${base} — ${notes.join(', ')}` : base
}

/* ------------------------------- JSON leaf edits ------------------------------- */

export type LeafPath = (string | number)[]

export function formatPath(path: LeafPath): string {
  return path
    .map((segment, index) =>
      typeof segment === 'number'
        ? `[${String(segment)}]`
        : index === 0
          ? segment
          : `.${segment}`,
    )
    .join('')
}

export function getAtPath(root: unknown, path: LeafPath): unknown {
  let node = root
  for (const segment of path) {
    if (node === null || typeof node !== 'object') return undefined
    node = (node as Record<string | number, unknown>)[segment]
  }
  return node
}

/** Immutable leaf replacement — structural changes stay source-mode-only (§4a). */
export function setAtPath(root: unknown, path: LeafPath, value: unknown): unknown {
  if (path.length === 0) return value
  const [head, ...rest] = path
  if (Array.isArray(root)) {
    const copy: unknown[] = [...(root as unknown[])]
    copy[head as number] = setAtPath(copy[head as number], rest, value)
    return copy
  }
  if (root !== null && typeof root === 'object') {
    const copy: Record<string, unknown> = { ...(root as Record<string, unknown>) }
    copy[String(head)] = setAtPath(copy[String(head)], rest, value)
    return copy
  }
  // The path pointed through a scalar — the caller staged against stale structure.
  return root
}

/** Staged leaf edits applied in order over the original document. */
export function applyLeafEdits(root: unknown, edits: { path: LeafPath; value: unknown }[]): unknown {
  return edits.reduce((current, edit) => setAtPath(current, edit.path, edit.value), root)
}

/* ------------------------------- source-mode gates ------------------------------- */

export const SOURCE_WARN_BYTES = 256 * 1024
export const SOURCE_BLOCK_BYTES = 5 * 1024 * 1024

export type SourceCheck =
  | { ok: true; value: unknown; warning?: string }
  | { ok: false; error: string }

/**
 * The §4a Source→Form / proceed gate: parse (with position), then byte-size pre-flight
 * (warn >256 KiB, hard-block >5 MiB — the write cap).
 */
export function checkSourceBuffer(buffer: string): SourceCheck {
  let value: unknown
  try {
    value = JSON.parse(buffer)
  } catch (error) {
    return { ok: false, error: describeJsonError(error, buffer) }
  }
  const bytes = serializedBytes(value)
  if (bytes > SOURCE_BLOCK_BYTES) {
    return { ok: false, error: 'the value exceeds the 5 MiB write cap — it cannot be saved' }
  }
  if (bytes > SOURCE_WARN_BYTES) {
    return {
      ok: true,
      value,
      warning: 'larger than 256 KiB — it will render truncated in ledgers after saving',
    }
  }
  return { ok: true, value }
}

function describeJsonError(error: unknown, buffer: string): string {
  const message = error instanceof Error ? error.message : String(error)
  // Older V8 reports "… at position N" — translate to line/col; newer V8 already
  // embeds a source snippet (and sometimes line/column) in the message itself.
  const match = /position (\d+)/.exec(message)
  if (match !== null && !/line \d+/.test(message)) {
    const position = Number(match[1])
    const before = buffer.slice(0, position)
    const line = before.split('\n').length
    const col = position - before.lastIndexOf('\n')
    return `invalid JSON at line ${String(line)}, column ${String(col)}: ${message}`
  }
  return `invalid JSON: ${message}`
}

/* ------------------------------- clearing ------------------------------- */

/** §4a: clearing is an explicit choice — "empty text" vs "no value (null)", never inferred. */
export type ClearChoice = 'empty-text' | 'null'

export function clearedValue(choice: ClearChoice): unknown {
  return choice === 'empty-text' ? '' : null
}

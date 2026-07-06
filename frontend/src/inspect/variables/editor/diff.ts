// Verification-screen derivations (§4a): the generated plain-language sentence and the
// structural path diff — both computed from the SAME request object as the payload, so
// sentence and payload can never disagree. Re-serialization formatting noise (key order,
// whitespace) never appears as a change: the diff walks VALUES, not text.
import { formatPath } from './editState'
import type { LeafPath } from './editState'

export interface PathChange {
  path: string
  kind: 'changed' | 'added' | 'removed'
  before?: unknown
  after?: unknown
}

/** Value-level structural diff between two JSON documents, as leaf path lines. */
export function structuralDiff(before: unknown, after: unknown): PathChange[] {
  const changes: PathChange[] = []
  walk(before, after, [], changes)
  return changes
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function walk(before: unknown, after: unknown, path: LeafPath, out: PathChange[]) {
  if (Object.is(before, after)) return
  if (isPlainObject(before) && isPlainObject(after)) {
    const keys = new Set([...Object.keys(before), ...Object.keys(after)])
    for (const key of [...keys].sort()) {
      const inBefore = key in before
      const inAfter = key in after
      if (inBefore && !inAfter) {
        out.push({ path: formatPath([...path, key]), kind: 'removed', before: before[key] })
      } else if (!inBefore && inAfter) {
        out.push({ path: formatPath([...path, key]), kind: 'added', after: after[key] })
      } else {
        walk(before[key], after[key], [...path, key], out)
      }
    }
    return
  }
  if (Array.isArray(before) && Array.isArray(after)) {
    const shared = Math.min(before.length, after.length)
    for (let index = 0; index < shared; index++) {
      walk(before[index], after[index], [...path, index], out)
    }
    for (let index = shared; index < before.length; index++) {
      out.push({ path: formatPath([...path, index]), kind: 'removed', before: before[index] })
    }
    for (let index = shared; index < after.length; index++) {
      out.push({ path: formatPath([...path, index]), kind: 'added', after: after[index] })
    }
    return
  }
  // Scalar vs scalar, or a structural type change — one leaf-level change line.
  out.push({
    path: path.length === 0 ? '(value)' : formatPath(path),
    kind: 'changed',
    before,
    after,
  })
}

/** Total scalar leaves — the "of 40 fields" denominator in the diff summary. */
export function countLeaves(value: unknown): number {
  if (isPlainObject(value)) {
    const keys = Object.keys(value)
    if (keys.length === 0) return 1
    return keys.reduce((sum, key) => sum + countLeaves(value[key]), 0)
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return 1
    return value.reduce<number>((sum, item) => sum + countLeaves(item), 0)
  }
  return 1
}

/** "1 of 40 fields changes: shipping.cost 0 → 12.50 — the other 39 are unchanged" */
export function diffSummary(changes: PathChange[], totalLeaves: number): string {
  if (changes.length === 0) return 'no value change — only formatting, which is not saved'
  const noun = changes.length === 1 ? 'fields changes' : 'fields change'
  const untouched = Math.max(0, totalLeaves - changes.length)
  const head =
    changes.length === 1
      ? `1 of ${String(totalLeaves)} ${noun}: ${changeLine(changes[0])}`
      : `${String(changes.length)} of ${String(totalLeaves)} ${noun}`
  return untouched > 0
    ? `${head} — the other ${String(untouched)} ${untouched === 1 ? 'is' : 'are'} unchanged`
    : head
}

export function changeLine(change: PathChange): string {
  switch (change.kind) {
    case 'added':
      return `${change.path} added: ${short(change.after)}`
    case 'removed':
      return `${change.path} removed (was ${short(change.before)})`
    case 'changed':
      return `${change.path} ${short(change.before)} → ${short(change.after)}`
  }
}

export function short(value: unknown): string {
  if (value === null) return 'null'
  if (value === undefined) return '(absent)'
  if (typeof value === 'string') {
    return value.length > 40 ? `"${value.slice(0, 40)}…"` : `"${value}"`
  }
  if (typeof value === 'object') {
    return Array.isArray(value) ? `[array of ${String(value.length)}]` : '{object}'
  }
  // Remaining primitives stringify faithfully; anything exotic states itself.
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value)
  }
  return '(unrepresentable)'
}

/* ------------------------- the generated sentence ------------------------- */

export interface SentenceInput {
  name: string
  before: unknown
  after: unknown
  typeLabel: string
  /** "applies to the whole case" vs the execution-local phrasing. */
  scopeLabel: string
  /** Business key when present, else the instance id. */
  targetLabel: string
  engineName: string
  environment?: string
}

/**
 * §4a: "Change orderTotal from 0 to 149.90 (number, applies to the whole case) on
 * order-4711 in billing-prod (PROD)". Structured values summarize as their change count.
 */
export function changeSentence(input: SentenceInput): string {
  const env =
    input.environment !== undefined ? ` (${input.environment.toUpperCase()})` : ''
  const structured =
    (input.before !== null && typeof input.before === 'object') ||
    (input.after !== null && typeof input.after === 'object')
  const valuePart = structured
    ? summarizeStructuredChange(input.before, input.after)
    : `from ${short(input.before)} to ${short(input.after)}`
  return `Change ${input.name} ${valuePart} (${input.typeLabel}, ${input.scopeLabel}) on ${input.targetLabel} in ${input.engineName}${env}`
}

function summarizeStructuredChange(before: unknown, after: unknown): string {
  const changes = structuralDiff(before, after)
  if (changes.length === 1) return `— ${changeLine(changes[0])} —`
  return `in ${String(changes.length)} places`
}

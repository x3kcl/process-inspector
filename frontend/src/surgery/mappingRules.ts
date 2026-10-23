// Activity-mapping shapes the migration document accepts (MigrationMapping.form on the BFF).
// Pairs are the editor's model; compileMappings folds them into one-to-one, fan-out
// (one→many) or fan-in (many→one). A connected many-to-many component is refused —
// the engine converter has no such form.

export interface ActivityPair {
  fromActivityId: string
  toActivityId: string
}

export type ActivityMapping =
  | { fromActivityId: string; toActivityId: string }
  | { fromActivityIds: string[]; toActivityId: string }
  | { fromActivityId: string; toActivityIds: string[] }

export type MappingCompile =
  { ok: true; mappings: ActivityMapping[] } | { ok: false; reason: string }

/** Same refusal the BFF raises from MigrationMapping.form(). */
export const MANY_TO_MANY_REASON =
  'many-to-many mapping is not supported by the engine — split it into separate mappings'

function blank(value: string): boolean {
  return value.trim() === ''
}

export function addPair(
  pairs: readonly ActivityPair[],
  fromActivityId: string,
  toActivityId: string,
): ActivityPair[] {
  if (blank(fromActivityId) || blank(toActivityId)) return [...pairs]
  const exists = pairs.some(
    (pair) => pair.fromActivityId === fromActivityId && pair.toActivityId === toActivityId,
  )
  if (exists) return [...pairs]
  return [...pairs, { fromActivityId, toActivityId }]
}

interface Endpoint {
  side: 'from' | 'to'
  id: string
}

/**
 * Fold pairs into engine mapping elements.
 * ponytail: one BFS over the pair list. Fine for a single definition's activities;
 * a graph library is not warranted at that size.
 */
export function compileMappings(pairs: readonly ActivityPair[]): MappingCompile {
  const fromTo = new Map<string, string[]>()
  const toFrom = new Map<string, string[]>()
  for (const pair of pairs) {
    if (blank(pair.fromActivityId) || blank(pair.toActivityId)) continue
    const targets = fromTo.get(pair.fromActivityId)
    if (targets === undefined) fromTo.set(pair.fromActivityId, [pair.toActivityId])
    else if (!targets.includes(pair.toActivityId)) targets.push(pair.toActivityId)
    const sources = toFrom.get(pair.toActivityId)
    if (sources === undefined) toFrom.set(pair.toActivityId, [pair.fromActivityId])
    else if (!sources.includes(pair.fromActivityId)) sources.push(pair.fromActivityId)
  }

  const seenFrom = new Set<string>()
  const mappings: ActivityMapping[] = []

  for (const start of fromTo.keys()) {
    if (seenFrom.has(start)) continue
    const sources: string[] = []
    const targets: string[] = []
    const seenTo = new Set<string>()
    const queue: Endpoint[] = [{ side: 'from', id: start }]
    while (queue.length > 0) {
      const item = queue.shift()
      if (item === undefined) break
      if (item.side === 'from') {
        if (seenFrom.has(item.id)) continue
        seenFrom.add(item.id)
        sources.push(item.id)
        for (const target of fromTo.get(item.id) ?? []) {
          if (!seenTo.has(target)) queue.push({ side: 'to', id: target })
        }
      } else if (!seenTo.has(item.id)) {
        seenTo.add(item.id)
        targets.push(item.id)
        for (const source of toFrom.get(item.id) ?? []) {
          if (!seenFrom.has(source)) queue.push({ side: 'from', id: source })
        }
      }
    }
    const folded = foldComponent(sources, targets)
    if (!folded.ok) return folded
    mappings.push(folded.mapping)
  }
  return { ok: true, mappings }
}

function foldComponent(
  sources: string[],
  targets: string[],
): { ok: true; mapping: ActivityMapping } | { ok: false; reason: string } {
  if (sources.length === 0 || targets.length === 0 || (sources.length > 1 && targets.length > 1)) {
    return { ok: false, reason: MANY_TO_MANY_REASON }
  }
  const source = sources[0]
  const target = targets[0]
  if (sources.length === 1 && targets.length === 1) {
    return { ok: true, mapping: { fromActivityId: source, toActivityId: target } }
  }
  if (sources.length === 1) {
    return { ok: true, mapping: { fromActivityId: source, toActivityIds: targets } }
  }
  return { ok: true, mapping: { fromActivityIds: sources, toActivityId: target } }
}

/** JSON the table/pair list will submit. Expert reset restores this string. */
export function resetToGenerated(pairs: readonly ActivityPair[]): string {
  const result = compileMappings(pairs)
  return JSON.stringify(result.ok ? result.mappings : [], null, 2)
}

export function expertIsDirty(generatedJson: string, expertText: string): boolean {
  return expertText !== generatedJson
}

type ReadString = { ok: true; value: string | undefined } | { ok: false; reason: string }
type ReadStrings = { ok: true; value: string[] | undefined } | { ok: false; reason: string }

function readOptionalString(value: unknown): ReadString {
  if (value === undefined || value === null) return { ok: true, value: undefined }
  if (typeof value !== 'string') return { ok: false, reason: 'Activity ids must be strings.' }
  if (blank(value)) return { ok: true, value: undefined }
  return { ok: true, value }
}

function readOptionalStrings(value: unknown): ReadStrings {
  if (value === undefined || value === null) return { ok: true, value: undefined }
  if (!Array.isArray(value)) {
    return { ok: false, reason: 'Activity id lists must be arrays of strings.' }
  }
  const ids: string[] = []
  for (const item of value) {
    if (typeof item !== 'string' || blank(item)) {
      return { ok: false, reason: 'Activity id lists must be arrays of strings.' }
    }
    ids.push(item)
  }
  return { ok: true, value: ids.length === 0 ? undefined : ids }
}

function parseOne(
  item: unknown,
): { ok: true; mapping: ActivityMapping } | { ok: false; reason: string } {
  if (typeof item !== 'object' || item === null || Array.isArray(item)) {
    return { ok: false, reason: 'Each mapping must be an object.' }
  }
  const record = item as Record<string, unknown>
  const fromSingle = readOptionalString(record.fromActivityId)
  if (!fromSingle.ok) return fromSingle
  const fromMulti = readOptionalStrings(record.fromActivityIds)
  if (!fromMulti.ok) return fromMulti
  const toSingle = readOptionalString(record.toActivityId)
  if (!toSingle.ok) return toSingle
  const toMulti = readOptionalStrings(record.toActivityIds)
  if (!toMulti.ok) return toMulti

  const fromOne = fromSingle.value
  const fromMany = fromMulti.value
  const toOne = toSingle.value
  const toMany = toMulti.value
  if ((fromOne === undefined) === (fromMany === undefined)) {
    return {
      ok: false,
      reason: 'A mapping must set exactly one of fromActivityId or fromActivityIds.',
    }
  }
  if ((toOne === undefined) === (toMany === undefined)) {
    return {
      ok: false,
      reason: 'A mapping must set exactly one of toActivityId or toActivityIds.',
    }
  }
  if (fromMany !== undefined && toMany !== undefined) {
    return { ok: false, reason: MANY_TO_MANY_REASON }
  }
  if (fromOne !== undefined && toOne !== undefined) {
    return { ok: true, mapping: { fromActivityId: fromOne, toActivityId: toOne } }
  }
  if (fromOne !== undefined && toMany !== undefined) {
    return { ok: true, mapping: { fromActivityId: fromOne, toActivityIds: toMany } }
  }
  if (fromMany !== undefined && toOne !== undefined) {
    return { ok: true, mapping: { fromActivityIds: fromMany, toActivityId: toOne } }
  }
  return { ok: false, reason: MANY_TO_MANY_REASON }
}

/** Parse the expert-mode document. The BFF runs the same shape check again on submit. */
export function parseMappingJson(text: string): MappingCompile {
  let value: unknown
  try {
    value = JSON.parse(text) as unknown
  } catch {
    return { ok: false, reason: 'Mapping JSON is not valid JSON.' }
  }
  if (!Array.isArray(value)) {
    return { ok: false, reason: 'Mapping JSON must be an array of mappings.' }
  }
  const mappings: ActivityMapping[] = []
  for (const item of value) {
    const parsed = parseOne(item)
    if (!parsed.ok) return parsed
    mappings.push(parsed.mapping)
  }
  return { ok: true, mappings }
}

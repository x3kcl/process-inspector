// The incident detail's per-engine × definition breakdown table (INCIDENT-LEDGER.md §8).
// `countsByEngine` is the exact same "engineId → 'defKey:vN' → count" shape triage's
// ErrorGroup carries (§3.1: the ledger's display blob is deliberately the same shape as the
// live aggregation it ingests from) — so this reuses triage/drill.ts's groupDefinitionCounts
// verbatim instead of re-deriving the def:key/version split a second time.
import { groupDefinitionCounts, type DefinitionCounts } from '../triage/drill'

export interface EngineBreakdown {
  engineId: string
  definitions: DefinitionCounts[]
  total: number
}

/** Folds the ledger's nested counts map into one row per engine, ordered by failure volume
 *  (then engineId) — the same ordering triage's own engine rows use. */
export function flattenBreakdown(
  countsByEngine: Record<string, Record<string, number>> | undefined,
): EngineBreakdown[] {
  const rows: EngineBreakdown[] = []
  for (const [engineId, byDefVersion] of Object.entries(countsByEngine ?? {})) {
    const definitions = groupDefinitionCounts(byDefVersion)
    const total = definitions.reduce((sum, definition) => sum + definition.total, 0)
    rows.push({ engineId, definitions, total })
  }
  rows.sort((a, b) => b.total - a.total || a.engineId.localeCompare(b.engineId))
  return rows
}

/** The card's compact "N engines · M definitions" summary line. */
export function engineSummary(countsByEngine: Record<string, Record<string, number>> | undefined): {
  engineCount: number
  definitionCount: number
} {
  const rows = flattenBreakdown(countsByEngine)
  const definitionKeys = new Set<string>()
  for (const row of rows) {
    for (const definition of row.definitions) definitionKeys.add(definition.definitionKey)
  }
  return { engineCount: rows.length, definitionCount: definitionKeys.size }
}

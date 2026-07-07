// Stage 0 → Stage 1 drill-through links (SPEC §4, R-SEM-12): every count on the landing
// is its own click target, and the produced filter state is echoed by the search page's
// compiled-criteria panel before the operator trusts it. The links reuse the M2b URL codec,
// so a drill-through IS a shareable search.
//
// Honesty note: /api/search has no definition-version filter yet, so a per-version click
// carries the tightest expressible scope — engine + definition key + FAILED/RETRYING. The
// version column in the grid and the criteria echo make the wider scope visible instead of
// pretending precision. The GROUP-level click, though, does carry the class signature
// (usability round 2): landing on the grid pre-scoped to ONE error class makes the
// select-all-matching-filter path the one-click whole-class action.
import type { ErrorGroup, InstanceStatus } from '../api/model'
import { encodeSearch } from '../search/urlState'

/** The failure lanes an ErrorGroup aggregates over (dead-letter + retries-left). */
const FAILURE_STATUSES: InstanceStatus[] = ['FAILED', 'RETRYING']

/** Splits the triage inner-map key "defKey:vN" (version suffix is normative). */
export function splitDefinitionCount(key: string): { definitionKey: string; version: string } {
  const at = key.lastIndexOf(':')
  if (at > 0 && /^v\d+$/.test(key.slice(at + 1))) {
    return { definitionKey: key.slice(0, at), version: key.slice(at + 1) }
  }
  return { definitionKey: key, version: '' }
}

/** One definition-version count inside one engine of one error group. */
export function definitionDrillParams(engineId: string, definitionKey: string): string {
  return encodeSearch({
    engineIds: [engineId],
    statuses: FAILURE_STATUSES,
    processDefinitionKey: definitionKey,
    sortBy: 'failureTime',
  }).toString()
}

/** The engine-level scope of a group (all definitions on that engine). */
export function engineDrillParams(engineId: string): string {
  return encodeSearch({
    engineIds: [engineId],
    statuses: FAILURE_STATUSES,
    sortBy: 'failureTime',
  }).toString()
}

/** The whole group: every engine it was observed on, scoped to THIS error class. */
export function groupDrillParams(group: ErrorGroup): string {
  const engineIds = Object.keys(group.countsByEngine ?? {}).sort()
  return encodeSearch({
    engineIds,
    statuses: FAILURE_STATUSES,
    signatureHash: group.signatureHash,
    sortBy: 'failureTime',
  }).toString()
}

/** A Stage 0 status tile: pre-filtered search on that status across all engines. */
export function statusDrillParams(status: InstanceStatus): string {
  return encodeSearch({ statuses: [status] }).toString()
}

export interface VersionCount {
  version: string
  count: number
}

export interface DefinitionCounts {
  definitionKey: string
  total: number
  versions: VersionCount[]
}

/**
 * Folds one engine's "defKey:vN" → count map into per-definition rows. Versions keep the
 * backend's zero-fill (a "v46: 0" beside "v47: 312" is the version-regression signal),
 * ordered newest-version-first; definitions ordered by failure volume.
 */
export function groupDefinitionCounts(byDefVersion: Record<string, number>): DefinitionCounts[] {
  const byKey = new Map<string, VersionCount[]>()
  for (const [rawKey, count] of Object.entries(byDefVersion)) {
    const { definitionKey, version } = splitDefinitionCount(rawKey)
    const list = byKey.get(definitionKey) ?? []
    list.push({ version, count })
    byKey.set(definitionKey, list)
  }
  const rows: DefinitionCounts[] = []
  for (const [definitionKey, versions] of byKey) {
    versions.sort((a, b) => versionNumber(b.version) - versionNumber(a.version))
    rows.push({
      definitionKey,
      total: versions.reduce((sum, v) => sum + v.count, 0),
      versions,
    })
  }
  rows.sort((a, b) => b.total - a.total || a.definitionKey.localeCompare(b.definitionKey))
  return rows
}

function versionNumber(version: string): number {
  const parsed = Number.parseInt(version.slice(1), 10)
  return Number.isFinite(parsed) ? parsed : -1
}

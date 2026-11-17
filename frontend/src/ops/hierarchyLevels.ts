// Per-level progress of a hierarchy migration job (#497). Such a job runs its deepest level
// first and a parent only after every planned child reached its target, so the drawer shows
// where the plan stands level by level. A job whose items carry no level is not a hierarchy job.
import type { BulkItemDto } from '../api/bulk'

export interface LevelProgress {
  level: number
  total: number
  /** Settled, in any terminal state — the level is finished when this equals total. */
  settled: number
  ok: number
  /** Not run because a child did not reach its target (or the job stopped first). */
  notRun: number
  failed: number
  unknown: number
  skipped: number
}

/** Deepest level first — the order the job dispatches them. Empty for a non-hierarchy job. */
export function levelProgress(items: BulkItemDto[]): LevelProgress[] {
  const byLevel = new Map<number, LevelProgress>()
  for (const item of items) {
    if (typeof item.level !== 'number') continue
    const row = byLevel.get(item.level) ?? {
      level: item.level,
      total: 0,
      settled: 0,
      ok: 0,
      notRun: 0,
      failed: 0,
      unknown: 0,
      skipped: 0,
    }
    row.total += 1
    if (item.state !== 'pending' && item.state !== 'dispatched') row.settled += 1
    if (item.state === 'ok') row.ok += 1
    else if (item.state === 'not_run') row.notRun += 1
    else if (item.state === 'failed') row.failed += 1
    else if (item.state === 'unknown') row.unknown += 1
    else if (item.state === 'skipped' || item.state === 'skipped_protected') row.skipped += 1
    byLevel.set(item.level, row)
  }
  return [...byLevel.values()].sort((a, b) => b.level - a.level)
}

/** "level 2 — 1 of 1 migrated" / "level 1 — 0 of 1 migrated · 1 not run". */
export function levelLine(row: LevelProgress): string {
  const parts = [`${String(row.ok)} of ${String(row.total)} migrated`]
  if (row.failed > 0) parts.push(`${String(row.failed)} failed`)
  if (row.unknown > 0) parts.push(`${String(row.unknown)} unknown`)
  if (row.skipped > 0) parts.push(`${String(row.skipped)} skipped`)
  if (row.notRun > 0) parts.push(`${String(row.notRun)} not run`)
  if (row.settled < row.total) parts.push(`${String(row.total - row.settled)} to go`)
  return `level ${String(row.level)} — ${parts.join(' · ')}`
}

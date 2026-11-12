import { describe, expect, it } from 'vitest'
import type { BulkItemDto } from '../api/bulk'
import { levelLine, levelProgress } from './hierarchyLevels'

const item = (level: number | undefined, state: string): BulkItemDto => ({
  engineId: 'engine-a',
  instanceId: `${String(level)}-${state}`,
  state,
  level,
})

describe('levelProgress — per-level progress of a hierarchy migration job (#497)', () => {
  it('is empty for a job whose items carry no level', () => {
    expect(levelProgress([item(undefined, 'ok'), item(undefined, 'failed')])).toEqual([])
  })

  it('lists the deepest level first, the order the job dispatches them', () => {
    const rows = levelProgress([item(0, 'pending'), item(2, 'ok'), item(1, 'dispatched')])
    expect(rows.map((row) => row.level)).toEqual([2, 1, 0])
  })

  it('counts a failed child and the parents it left not run', () => {
    const rows = levelProgress([
      item(2, 'failed'),
      item(2, 'ok'),
      item(1, 'not_run'),
      item(1, 'ok'),
      item(0, 'not_run'),
    ])
    expect(rows[0]).toMatchObject({ level: 2, total: 2, settled: 2, ok: 1, failed: 1 })
    expect(rows[1]).toMatchObject({ level: 1, total: 2, settled: 2, ok: 1, notRun: 1 })
    expect(rows[2]).toMatchObject({ level: 0, total: 1, settled: 1, ok: 0, notRun: 1 })
  })

  it('counts unknown and both skipped states', () => {
    const [row] = levelProgress([
      item(1, 'unknown'),
      item(1, 'skipped'),
      item(1, 'skipped_protected'),
    ])
    expect(row).toMatchObject({ unknown: 1, skipped: 2, settled: 3 })
  })
})

describe('levelLine', () => {
  it('reads plainly for a finished and a blocked level', () => {
    const rows = levelProgress([item(2, 'failed'), item(1, 'not_run'), item(0, 'pending')])
    expect(rows.map(levelLine)).toEqual([
      'level 2 — 0 of 1 migrated · 1 failed',
      'level 1 — 0 of 1 migrated · 1 not run',
      'level 0 — 0 of 1 migrated · 1 to go',
    ])
  })
})

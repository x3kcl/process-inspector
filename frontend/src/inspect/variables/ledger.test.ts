import { describe, expect, it } from 'vitest'
import type { VariableEntry } from './ledger'
import {
  PROCESS_GROUP_LABEL,
  buildLedger,
  formatBytes,
  structuredSummary,
  typeChip,
  valuePreview,
} from './ledger'

const entry = (partial: Partial<VariableEntry> & { name: string }): VariableEntry => ({
  scope: 'process',
  value: undefined,
  ...partial,
})

describe('typeChip — plain-language types (SPEC §4)', () => {
  it('maps engine types to operator language', () => {
    expect(typeChip(entry({ name: 'a', engineType: 'string', value: 'x' }))).toBe('text')
    expect(typeChip(entry({ name: 'a', engineType: 'long', value: 42 }))).toBe('number')
    expect(typeChip(entry({ name: 'a', engineType: 'boolean', value: false }))).toBe('yes-no')
    expect(typeChip(entry({ name: 'a', engineType: 'date', value: '2026-07-06T10:00:00Z' }))).toBe(
      'date',
    )
    expect(typeChip(entry({ name: 'a', engineType: 'json', value: { x: 1 } }))).toBe('structured')
    expect(typeChip(entry({ name: 'a', engineType: 'serializable', value: 'rO0AB…' }))).toBe(
      'Java object',
    )
  })

  it('null value wins over any declared type', () => {
    expect(typeChip(entry({ name: 'a', engineType: 'string', value: null }))).toBe('empty')
  })

  it('falls back to the value shape when no type is declared', () => {
    expect(typeChip(entry({ name: 'a', value: 3.14 }))).toBe('number')
    expect(typeChip(entry({ name: 'a', value: { nested: true } }))).toBe('structured')
    expect(typeChip(entry({ name: 'a', value: 'plain' }))).toBe('text')
  })
})

describe('valuePreview — never a raw JSON wall (R-UXQ-13)', () => {
  it('null is explicit, never blank', () => {
    expect(valuePreview(entry({ name: 'a', value: null }))).toEqual({
      text: '(no value / null)',
      muted: true,
      expandable: false,
    })
  })

  it('booleans are words, never glyphs', () => {
    expect(valuePreview(entry({ name: 'a', engineType: 'boolean', value: false })).text).toBe(
      'No (false)',
    )
    expect(valuePreview(entry({ name: 'a', engineType: 'boolean', value: true })).text).toBe(
      'Yes (true)',
    )
  })

  it('empty string is tellable-apart from null', () => {
    expect(valuePreview(entry({ name: 'a', engineType: 'string', value: '' })).text).toBe(
      '(empty text)',
    )
  })

  it('structured values render a summary, expandable — not their body', () => {
    const preview = valuePreview(
      entry({ name: 'a', engineType: 'json', value: { x: 1, y: 2, z: 3 } }),
    )
    expect(preview.text).toMatch(/^object · 3 fields · \d/)
    expect(preview.expandable).toBe(true)
  })

  it('long text truncates with an explicit char count', () => {
    const preview = valuePreview(entry({ name: 'a', engineType: 'string', value: 'x'.repeat(500) }))
    expect(preview.text).toContain('… (500 chars)')
    expect(preview.expandable).toBe(true)
  })

  it('serializables read intentionally locked, never broken', () => {
    const preview = valuePreview(entry({ name: 'a', engineType: 'serializable', value: 'blob' }))
    expect(preview.text).toContain('read-only')
    expect(preview.expandable).toBe(false)
  })
})

describe('server-truncated rows (SPEC §4 size safeguards)', () => {
  const truncated = entry({
    name: 'payload',
    engineType: 'json',
    value: null, // the server ships null for over-cap values
    truncated: true,
    sizeBytes: 412 * 1024,
  })

  it('a truncated null is NOT an empty chip — the declared type wins', () => {
    expect(typeChip(truncated)).toBe('structured')
    expect(typeChip({ ...truncated, engineType: 'string' })).toBe('text')
  })

  it('states the cap and the real size instead of pretending emptiness', () => {
    const preview = valuePreview(truncated)
    expect(preview.text).toContain('exceeds the 256 KiB preview cap')
    expect(preview.text).toContain('412 KiB')
    expect(preview.expandable).toBe(false)
  })

  it('after the explicit full load, the value renders through the normal pipeline', () => {
    const loaded = { ...truncated, value: { huge: true }, fullyLoaded: true }
    expect(typeChip(loaded)).toBe('structured')
    expect(valuePreview(loaded).expandable).toBe(true)
  })
})

describe('structuredSummary / formatBytes', () => {
  it('summarizes arrays with item counts', () => {
    expect(structuredSummary([1, 2, 3, 4])).toMatch(/^array · 4 items · /)
  })
  it('human-readable sizes', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2150)).toBe('2.1 KiB')
    expect(formatBytes(3 * 1024 * 1024)).toBe('3 MiB')
  })
})

describe('buildLedger — scope grouping (SPEC §4)', () => {
  const entries: VariableEntry[] = [
    entry({ name: 'orderTotal', engineType: 'double', value: 149.9 }),
    entry({ name: 'customer', engineType: 'string', value: 'k.meier' }),
    entry({
      name: 'lineItem',
      scope: 'local',
      executionLabel: 'Validate line item · instance 3 of 12',
      engineType: 'json',
      value: { sku: 'A' },
    }),
    entry({
      name: 'orderTotal',
      scope: 'local',
      executionLabel: 'Validate line item · instance 3 of 12',
      engineType: 'double',
      value: 12.5,
    }),
  ]

  it('process scope first, then step-local groups; rows alphabetical', () => {
    const groups = buildLedger(entries)
    expect(groups.map((group) => group.label)).toEqual([
      PROCESS_GROUP_LABEL,
      'Step-local: Validate line item · instance 3 of 12',
    ])
    expect(groups[0]?.rows.map((row) => row.entry.name)).toEqual(['customer', 'orderTotal'])
  })

  it('a local variable shadowing a process-scope name is badged', () => {
    const groups = buildLedger(entries)
    const local = groups[1]?.rows ?? []
    expect(local.find((row) => row.entry.name === 'orderTotal')?.shadowsProcessScope).toBe(true)
    expect(local.find((row) => row.entry.name === 'lineItem')?.shadowsProcessScope).toBe(false)
  })

  it('locals without an execution label still group readably', () => {
    const groups = buildLedger([entry({ name: 'x', scope: 'local', value: 1 })])
    expect(groups[0]?.label).toBe('Step-local: unnamed execution')
  })
})

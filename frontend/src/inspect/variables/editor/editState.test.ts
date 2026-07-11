import { describe, expect, it } from 'vitest'
import {
  applyLeafEdits,
  asEditableType,
  checkSourceBuffer,
  clearedValue,
  editGateReason,
  formatPath,
  getAtPath,
  parseDateInput,
  parseNumberInput,
  setAtPath,
  textEcho,
} from './editState'

describe('editGateReason — greyed-with-reason ladder (§4a Entry)', () => {
  it('locks read-only engines, ended instances and serializables, in that order', () => {
    expect(
      editGateReason({ scope: 'process', instanceEnded: false, engineMode: 'READ_ONLY' }),
    ).toContain('read-only')
    // W1#4 (theme T6): the registry wire value is hyphenated — it must lock the pencil too.
    expect(
      editGateReason({ scope: 'process', instanceEnded: false, engineMode: 'read-only' }),
    ).toContain('read-only')
    expect(editGateReason({ scope: 'process', instanceEnded: true })).toContain('ended')
    expect(
      editGateReason({ engineType: 'serializable', scope: 'process', instanceEnded: false }),
    ).toContain('serializable')
  })
  it('permits editable types on both process and execution-local scope', () => {
    expect(
      editGateReason({ engineType: 'string', scope: 'process', instanceEnded: false }),
    ).toBeNull()
    expect(editGateReason({ scope: 'process', instanceEnded: false })).toBeNull()
    // Step-local edits are supported now (scoped read/CAS/write leg) — an editable
    // execution-local value is no longer gated off.
    expect(
      editGateReason({ engineType: 'integer', scope: 'local', instanceEnded: false }),
    ).toBeNull()
    // …but the type/state gates still apply on local scope.
    expect(editGateReason({ scope: 'local', instanceEnded: true })).toContain('ended')
    expect(
      editGateReason({ engineType: 'serializable', scope: 'local', instanceEnded: false }),
    ).toContain('serializable')
  })
})

describe('parseNumberInput — subtype ranges and the parsed echo', () => {
  it('echoes the exact stored value ("the echo IS the contract")', () => {
    const result = parseNumberInput(' 42 ', 'integer')
    expect(result).toEqual({ ok: true, value: 42, echo: 'will be stored as integer 42' })
  })
  it('rejects fractions for integer subtypes but not double', () => {
    expect(parseNumberInput('1.5', 'long').ok).toBe(false)
    expect(parseNumberInput('1.5', 'double')).toMatchObject({ ok: true, value: 1.5 })
  })
  it('enforces short/integer ranges', () => {
    expect(parseNumberInput('32768', 'short').ok).toBe(false)
    expect(parseNumberInput('32767', 'short').ok).toBe(true)
    expect(parseNumberInput('2147483648', 'integer').ok).toBe(false)
  })
  it('refuses unsafe long magnitudes instead of silently rounding', () => {
    expect(parseNumberInput('9007199254740993', 'long').ok).toBe(false)
  })
  it('rejects non-numeric noise', () => {
    expect(parseNumberInput('42abc', 'integer').ok).toBe(false)
    expect(parseNumberInput('', 'integer').ok).toBe(false)
  })
})

describe('parseDateInput — timezone honesty (§4a)', () => {
  it('rejects offset-less input outright', () => {
    const result = parseDateInput('2026-07-06T14:30')
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toContain('offset required')
  })
  it('normalizes an offset form to the exact UTC instant that will be sent', () => {
    const result = parseDateInput('2026-07-06T14:30:00+02:00')
    expect(result).toMatchObject({ ok: true, value: '2026-07-06T12:30:00.000Z' })
  })
  it('accepts Z directly', () => {
    expect(parseDateInput('2026-07-06T12:30:00Z').ok).toBe(true)
  })
})

describe('textEcho — whitespace made visible', () => {
  it('names leading and trailing spaces', () => {
    expect(textEcho('  x ')).toContain('2 leading spaces')
    expect(textEcho('  x ')).toContain('1 trailing space')
  })
  it('stays quiet for clean text', () => {
    expect(textEcho('clean')).toBe('will be stored as text (5 chars)')
  })
})

describe('JSON leaf paths', () => {
  const doc = { shipping: { cost: 0 }, items: [{ sku: 'a' }, { sku: 'b' }] }
  it('formats paths with dots and array indices', () => {
    expect(formatPath(['shipping', 'cost'])).toBe('shipping.cost')
    expect(formatPath(['items', 1, 'sku'])).toBe('items[1].sku')
  })
  it('reads and immutably replaces leaves', () => {
    expect(getAtPath(doc, ['items', 1, 'sku'])).toBe('b')
    const next = setAtPath(doc, ['shipping', 'cost'], 12.5) as typeof doc
    expect(next.shipping.cost).toBe(12.5)
    expect(doc.shipping.cost).toBe(0)
    expect(next.items).toBe(doc.items)
  })
  it('applies staged edits in order', () => {
    const next = applyLeafEdits(doc, [
      { path: ['shipping', 'cost'], value: 9 },
      { path: ['items', 0, 'sku'], value: 'z' },
    ]) as typeof doc
    expect(next.shipping.cost).toBe(9)
    expect(next.items[0].sku).toBe('z')
  })
})

describe('checkSourceBuffer — the source-mode gates', () => {
  it('reports parse errors as invalid JSON with the engine detail', () => {
    const result = checkSourceBuffer('{\n  "a": 1,\n  "b": }')
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toContain('invalid JSON')
  })
  it('passes valid JSON through', () => {
    expect(checkSourceBuffer('{"a": 1}')).toEqual({ ok: true, value: { a: 1 } })
  })
  it('warns above 256 KiB and blocks above 5 MiB', () => {
    const warnSized = JSON.stringify({ blob: 'x'.repeat(300 * 1024) })
    const warned = checkSourceBuffer(warnSized)
    expect(warned.ok).toBe(true)
    if (warned.ok) expect(warned.warning).toContain('256 KiB')
    const blockSized = JSON.stringify({ blob: 'x'.repeat(6 * 1024 * 1024) })
    expect(checkSourceBuffer(blockSized).ok).toBe(false)
  })
})

describe('clearing is explicit (§4a)', () => {
  it('distinguishes empty text from null', () => {
    expect(clearedValue('empty-text')).toBe('')
    expect(clearedValue('null')).toBeNull()
  })
})

describe('asEditableType', () => {
  it('maps engine types case-insensitively and rejects the rest', () => {
    expect(asEditableType('String')).toBe('string')
    expect(asEditableType('serializable')).toBeNull()
    expect(asEditableType(undefined)).toBeNull()
  })
})

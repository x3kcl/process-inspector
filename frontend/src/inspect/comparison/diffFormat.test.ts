import { describe, expect, it } from 'vitest'
import type { TimingDelta, VariableDto } from '../../api/model'
import {
  barWidthPct,
  changeGlyph,
  deltaPhrase,
  isDivergent,
  renderValue,
  timingScale,
} from './diffFormat'

const variable = (over: Partial<VariableDto>): VariableDto => ({
  name: 'v',
  type: 'string',
  value: 'x',
  truncated: false,
  scope: 'global',
  ...over,
})

const timing = (over: Partial<TimingDelta>): TimingDelta => ({
  activityId: 'a',
  subjectOccurrences: 1,
  siblingOccurrences: 1,
  subjectUnfinished: false,
  ...over,
})

describe('sibling-diff formatting', () => {
  it('maps every change kind to a distinct non-colour glyph (SPEC §10a)', () => {
    const glyphs = [
      changeGlyph('CHANGED'),
      changeGlyph('ONLY_IN_SUBJECT'),
      changeGlyph('ONLY_IN_SIBLING'),
      changeGlyph('DIFFER_BEYOND_PREVIEW'),
      changeGlyph('SAME'),
    ]
    expect(new Set(glyphs).size).toBe(5) // all distinct — the glyph alone disambiguates
  })

  it('treats only SAME as non-divergent', () => {
    expect(isDivergent('SAME')).toBe(false)
    expect(isDivergent('CHANGED')).toBe(true)
    expect(isDivergent('DIFFER_BEYOND_PREVIEW')).toBe(true)
  })

  it('renders a truncated value as its size, never a fetched blob', () => {
    expect(
      renderValue(variable({ truncated: true, value: null, sizeBytes: 512 * 1024 })),
    ).toContain('512 KiB')
    expect(renderValue(undefined)).toBe('—')
    expect(renderValue(variable({ value: null }))).toBe('(null)')
    expect(renderValue(variable({ type: 'json', value: { a: 1 } }))).toBe('{"a":1}')
    expect(renderValue(variable({ type: 'integer', value: 42 }))).toBe('42')
  })

  it('scales timing bars to the largest completed duration across both sides', () => {
    const scale = timingScale([timing({ subjectMs: 300 }), timing({ siblingMs: 900 })])
    expect(scale.maxMs).toBe(900)
    expect(barWidthPct(900, scale)).toBe(100)
    expect(barWidthPct(0, scale)).toBe(0)
    expect(barWidthPct(1, scale)).toBe(1.5) // floored so a sliver stays visible
  })

  it('signs the delta and is honest about the stalled step', () => {
    expect(deltaPhrase(timing({ subjectMs: 500, siblingMs: 100, deltaMs: 400 }))).toBe(
      'slower by 400ms',
    )
    expect(deltaPhrase(timing({ subjectMs: 100, siblingMs: 500, deltaMs: -400 }))).toBe(
      'faster by 400ms',
    )
    expect(deltaPhrase(timing({ subjectUnfinished: true, siblingMs: 80 }))).toBe(
      'never completed here',
    )
    expect(deltaPhrase(timing({ siblingMs: 80 }))).toBe('not on the failed run')
    expect(deltaPhrase(timing({ subjectMs: 80 }))).toBe('not on the sibling')
  })
})

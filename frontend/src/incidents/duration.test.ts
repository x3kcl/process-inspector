import { describe, expect, it } from 'vitest'
import { episodeDurationLabel } from './duration'

describe('episodeDurationLabel', () => {
  it('labels a live episode (no endedAt) as ongoing', () => {
    expect(episodeDurationLabel({})).toBe('ongoing')
    expect(episodeDurationLabel({ endedAt: '' })).toBe('ongoing')
  })

  it('formats a closed episode from durationSeconds', () => {
    expect(episodeDurationLabel({ endedAt: '2026-07-18T12:00:00Z', durationSeconds: 45 })).toBe(
      '45s',
    )
    expect(episodeDurationLabel({ endedAt: '2026-07-18T12:00:00Z', durationSeconds: 3_700 })).toBe(
      '1h 1m',
    )
  })

  it('coalesces a missing durationSeconds on a closed episode to 0s, never crashing', () => {
    expect(episodeDurationLabel({ endedAt: '2026-07-18T12:00:00Z' })).toBe('0s')
  })
})

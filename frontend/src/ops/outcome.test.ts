import { describe, expect, it } from 'vitest'
import { outcomeClassName, outcomeLabel } from './outcome'

describe('outcomeLabel — usability round 1, Theme G', () => {
  it('labels a retry-job ok item "re-queued" — it has not succeeded yet', () => {
    expect(outcomeLabel('retry-job', 'ok')).toBe('re-queued')
  })

  it('labels any other verb’s ok item "done"', () => {
    expect(outcomeLabel('suspend', 'ok')).toBe('done')
    expect(outcomeLabel('activate', 'ok')).toBe('done')
    expect(outcomeLabel(undefined, 'ok')).toBe('done')
  })

  it('leaves every other state label unchanged', () => {
    expect(outcomeLabel('retry-job', 'failed')).toBe('failed')
    expect(outcomeLabel('retry-job', 'skipped')).toBe('skipped')
    expect(outcomeLabel('retry-job', 'skipped_protected')).toBe('skipped (protected)')
    expect(outcomeLabel('retry-job', 'unknown')).toBe('unknown')
    expect(outcomeLabel('retry-job', 'not_run')).toBe('not run')
  })
})

describe('outcomeClassName', () => {
  it('gives retry-job+ok its OWN amber modifier, never the green outcome-ok', () => {
    expect(outcomeClassName('retry-job', 'ok')).toBe('outcome-requeued')
  })

  it('keeps the existing per-state modifier for everything else', () => {
    expect(outcomeClassName('suspend', 'ok')).toBe('outcome-ok')
    expect(outcomeClassName('retry-job', 'failed')).toBe('outcome-failed')
    expect(outcomeClassName('retry-job', 'skipped_protected')).toBe('outcome-skipped-protected')
    expect(outcomeClassName('retry-job', 'not_run')).toBe('outcome-not-run')
  })
})

import { describe, expect, it } from 'vitest'
import {
  auditOutcomeView,
  outcomeCellLabel,
  outcomeClassName,
  outcomeIsDispatchOnly,
  outcomeLabel,
} from './outcome'

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

describe('outcomeIsDispatchOnly — usability round 2, Theme T8', () => {
  it('flags retry-job+ok as a dispatch state, not a success verdict', () => {
    expect(outcomeIsDispatchOnly('retry-job', 'ok')).toBe(true)
  })

  it('never flags real verdicts or other verbs', () => {
    expect(outcomeIsDispatchOnly('suspend', 'ok')).toBe(false)
    expect(outcomeIsDispatchOnly('retry-job', 'failed')).toBe(false)
    expect(outcomeIsDispatchOnly('retry-job', 'unknown')).toBe(false)
    expect(outcomeIsDispatchOnly(undefined, 'ok')).toBe(false)
  })
})

describe('outcomeCellLabel — the per-item Outcome cell text', () => {
  it('renders re-queued with the inline not-a-verdict disclaimer', () => {
    expect(outcomeCellLabel('retry-job', 'ok')).toBe('re-queued — not yet succeeded; verify')
  })

  it('leaves real verdicts identical to outcomeLabel', () => {
    expect(outcomeCellLabel('suspend', 'ok')).toBe('done')
    expect(outcomeCellLabel('retry-job', 'failed')).toBe('failed')
    expect(outcomeCellLabel('retry-job', 'unknown')).toBe('unknown')
    expect(outcomeCellLabel('retry-job', 'not_run')).toBe('not run')
  })
})

describe('auditOutcomeView — verdict words, never raw store internals (Theme T8 / R-UXQ-05)', () => {
  it('renders ok with a wire-null httpStatus as "done" — never "ok · null"', () => {
    const view = auditOutcomeView('edit-variable', 'ok', null)
    expect(view.label).toBe('done')
    expect(view.label).not.toContain('null')
    expect(view.className).toBe('outcome-ok')
  })

  it('keeps retry honesty: an ok retry-job audit row reads "re-queued", amber', () => {
    const view = auditOutcomeView('retry-job', 'ok', 204)
    expect(view.label).toBe('re-queued')
    expect(view.className).toBe('outcome-requeued')
  })

  it('unwraps the bulk action prefix before mapping the verb', () => {
    expect(auditOutcomeView('bulk:retry-job', 'ok', null).label).toBe('re-queued')
    expect(auditOutcomeView('bulk:suspend', 'ok', null).label).toBe('done')
  })

  it('appends the HTTP status as evidence on non-success verdicts only', () => {
    expect(auditOutcomeView('suspend', 'failed', 409).label).toBe('failed · HTTP 409')
    expect(auditOutcomeView('suspend', 'unknown', 502).label).toBe('unknown · HTTP 502')
    expect(auditOutcomeView('suspend', 'ok', 204).label).toBe('done')
  })

  it('renders PENDING as "pending" with its own class, and a missing outcome as "unknown"', () => {
    const pending = auditOutcomeView('retry-job', 'PENDING', null)
    expect(pending.label).toBe('pending')
    expect(pending.className).toBe('outcome-pending')
    expect(auditOutcomeView('retry-job', undefined, null).label).toBe('unknown')
  })

  it('keeps the raw internals available as tooltip evidence', () => {
    expect(auditOutcomeView('suspend', 'ok', 204).title).toContain('HTTP 204')
    expect(auditOutcomeView('suspend', 'ok', null).title).toContain('no HTTP status')
    expect(auditOutcomeView('suspend', 'ok', 204).title).toContain('ok')
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

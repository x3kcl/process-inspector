// Pure presentation helpers for team (shared) views — rung-1 testable, no React.
import type { TeamViewDto } from '../api/model'

/** A team view whose scoped engine is gone (SHARED-VIEWS.md §4.5): grey it, never a live link. */
export function isDangling(view: TeamViewDto): boolean {
  return view.danglingReason !== undefined && view.danglingReason !== ''
}

/** Human scope phrase for the chip tooltip — never color-only (R-UXQ-01), always words. */
export function scopeLabel(view: TeamViewDto): string {
  const engine = view.scopeEngineId ?? '*'
  const tenant = view.scopeTenantId ?? '*'
  if (engine === '*' && tenant === '*') return 'all engines'
  if (engine === '*') return `tenant ${tenant}`
  if (tenant === '*') return `engine ${engine}`
  return `engine ${engine} · tenant ${tenant}`
}

/** The chip's hover/title text: author + scope (+ description/runbook if present, + dangling reason). */
export function teamViewTitle(view: TeamViewDto): string {
  const parts = [`Team view · by ${view.author ?? 'unknown'} · ${scopeLabel(view)}`]
  if (view.description !== undefined && view.description !== '') {
    parts.push(view.description)
  }
  if (view.runbookUrl !== undefined && view.runbookUrl !== '') {
    parts.push(view.runbookUrl)
  }
  if (isDangling(view)) parts.push(`⚠ ${String(view.danglingReason)}`)
  return parts.join('\n')
}

// The R-AUD-05 shift report (SPEC §9, usability W3-1): "my activity, this shift" as
// one-click plain text for the handover ticket/chat. UNKNOWN outcomes come FIRST under
// NEEDS VERIFICATION — the next shift's first job is verifying what may or may not have
// executed (corrective-actions §4: an unknown mutation is never auto-retried, only
// re-checked). Machine-facing text: UTC ISO only (R-UXQ-07), via the shared toUtcIso.
import type { AuditEntryDto } from '../api/model'
import { toUtcIso } from '../lib/format'
import { auditOutcomeView } from './outcome'

/** Default shift window: the last 8 hours (SPEC §9 "my activity, this shift"). */
export const SHIFT_HOURS = 8

/** Shift start = now − SHIFT_HOURS, as the UTC ISO the `since` filter expects. */
export function shiftStartIso(nowMs = Date.now()): string {
  return new Date(nowMs - SHIFT_HOURS * 3_600_000).toISOString()
}

/** An outcome the next shift must verify: unknown, or still PENDING at handover time. */
function needsVerification(entry: AuditEntryDto): boolean {
  return entry.outcome === undefined || entry.outcome === 'unknown' || entry.outcome === 'PENDING'
}

function target(entry: AuditEntryDto): string {
  const engine = entry.engineId ?? '?'
  return typeof entry.instanceId === 'string' && entry.instanceId !== ''
    ? `${engine}:${entry.instanceId}`
    : engine
}

function line(entry: AuditEntryDto): string {
  const verdict = auditOutcomeView(entry.action, entry.outcome, entry.httpStatus).label
  const parts = [`- ${toUtcIso(entry.ts)}  ${entry.action ?? '?'}  ${target(entry)} — ${verdict}`]
  if (entry.reason !== undefined && entry.reason !== '') parts.push(`reason: ${entry.reason}`)
  if (entry.ticketId !== undefined && entry.ticketId !== '') parts.push(`ticket: ${entry.ticketId}`)
  return parts.join(' · ')
}

function block(entries: AuditEntryDto[]): string {
  return entries.length === 0 ? '(none)' : entries.map(line).join('\n')
}

const byTsAscending = (a: AuditEntryDto, b: AuditEntryDto) => (a.ts ?? '').localeCompare(b.ts ?? '')

export interface ShiftReportContext {
  /** Whose shift this is — the actor filter, falling back to the signed-in user. */
  actor: string
  /** The window start the log was filtered with (ISO); '' renders as "start of log". */
  sinceIso: string
  nowMs: number
}

/** The plain-text handover report over the rows the operations log currently shows. */
export function buildShiftReport(entries: AuditEntryDto[], context: ShiftReportContext): string {
  const unknowns = entries.filter(needsVerification).sort(byTsAscending)
  const rest = entries.filter((entry) => !needsVerification(entry)).sort(byTsAscending)
  const window = `${context.sinceIso === '' ? 'start of log' : toUtcIso(context.sinceIso)} → ${toUtcIso(
    new Date(context.nowMs).toISOString(),
  )}`
  return [
    `Shift report — ${context.actor === '' ? '(all actors)' : context.actor} — ${window} (UTC) — ${String(
      entries.length,
    )} audited action${entries.length === 1 ? '' : 's'}`,
    '',
    'NEEDS VERIFICATION (outcome unknown — re-check instance state before handover):',
    block(unknowns),
    '',
    'Other actions (chronological):',
    block(rest),
  ].join('\n')
}

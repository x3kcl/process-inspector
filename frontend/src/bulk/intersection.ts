// The Intersection Rule (SPEC §7, binding): the bulk bar offers EXACTLY the verbs valid
// for EVERY selected row — one ineligible row disables the verb for the whole selection,
// disabled-with-reason naming the offender (greyed never hidden, SPEC §6). Protected
// instances are auto-excluded from the effective selection and surfaced as a badge.
// Pure and tested; the components render its output verbatim.
//
// Usability round 1: `reason` is the SHORT copy for the visible ActionHint (A-copy —
// `Blocked: {offender} {why}` for business logic, `Requires RESPONDER — you are {ROLE}`
// for the role floor); `detail` is the long explanation that moves to the button's title.
import type { EngineDto, ProcessInstanceRow } from '../api/model'
import type { RoleHint } from '../actions/catalog'
import { roleAtLeast } from '../actions/catalog'

// The server-enforced bulk caps (W2 #5, R-NFR-01) — mirrors of the backend's
// BulkJob.ITEM_CAP / BulkJob.FILTER_ITEM_CAP. Disclosed VERBATIM in the confirm copy so
// the numbers stop being a surprise refusal; the BFF stays the real gate.
export const BULK_CAP = 200
export const BULK_FILTER_CAP = 5000

/** The one cap-disclosure sentence per submit door (R-NFR-01) — rendered verbatim. */
export function bulkCapNote(scope: 'selection' | 'filter'): string {
  const filterCap = BULK_FILTER_CAP.toLocaleString('en-US')
  return scope === 'selection'
    ? `Capped at ${String(BULK_CAP)} instances per bulk job (server-enforced). For larger sets — up to ${filterCap} — use "Select all matching filter…", the filter-scope bulk.`
    : `Capped at ${filterCap} instances per bulk job (server-enforced) — a filter resolving to more is refused; narrow it and run in slices.`
}

const ROLE_FLOOR: RoleHint = 'RESPONDER'

/** The v1 bulk verb set (queue-state verbs — destructive bulk is the tier-4 wizard). */
export const BULK_VERB_IDS = ['retry-job', 'suspend', 'activate'] as const
export type BulkVerbId = (typeof BULK_VERB_IDS)[number]

export interface BulkVerbOffer {
  verb: BulkVerbId
  label: string
  /** SPEC §5.0 plain-language secondary label. */
  plain: string
  enabled: boolean
  /** SHORT gate name (Theme A-copy) — the FIRST offending row, the cap, emptiness, or the
   *  role floor. Rendered verbatim by the ActionHint. */
  reason?: string
  /** The long explanation — surfaces as the button's title, never the visible hint. */
  detail?: string
}

export interface SelectionPlan {
  /** Rows that will actually be submitted (protected rows excluded). */
  targets: ProcessInstanceRow[]
  /** Auto-excluded protected rows — badge "N protected instances excluded". */
  protectedExcluded: number
  /** Rows whose protection state is UNKNOWN (store unreachable) — submitted anyway;
   *  the BFF guard settles them as skipped_protected per item. */
  protectionUnknown: number
  overCap: boolean
  offers: BulkVerbOffer[]
}

/** null = row is eligible; otherwise the short `why` clause (Blocked: {offender} {why})
 *  and the long `detail` clause naming why THIS row blocks the verb. */
interface RowBlocker {
  why: string
  detail: string
}

interface VerbRule {
  verb: BulkVerbId
  label: string
  plain: string
  blocker: (row: ProcessInstanceRow) => RowBlocker | null
}

const RULES: VerbRule[] = [
  {
    verb: 'retry-job',
    label: 'Retry dead-letter jobs',
    plain: 'run the failed steps again',
    blocker: (row) =>
      row.flags?.hasDeadLetterJobs === true
        ? null
        : { why: 'has nothing to retry', detail: 'has no dead-letter job (nothing to retry)' },
  },
  {
    verb: 'suspend',
    label: 'Suspend',
    plain: 'pause these cases',
    blocker: (row) => {
      if (row.flags?.ended === true || row.status === 'COMPLETED') {
        return { why: 'has ended', detail: 'has ended' }
      }
      if (row.flags?.suspended === true || row.status === 'SUSPENDED') {
        return { why: 'is already suspended', detail: 'is already suspended' }
      }
      return null
    },
  },
  {
    verb: 'activate',
    label: 'Activate',
    plain: 'resume these cases',
    blocker: (row) => {
      if (row.flags?.ended === true || row.status === 'COMPLETED') {
        return { why: 'has ended', detail: 'has ended' }
      }
      if (!(row.flags?.suspended === true || row.status === 'SUSPENDED')) {
        return { why: 'is not suspended', detail: 'is not suspended' }
      }
      return null
    },
  },
]

function rowName(row: ProcessInstanceRow): string {
  // Jackson serializes an absent business key as JSON null, so an undefined-only check
  // would render a literal "null" in the visible hint — guard both.
  return typeof row.businessKey === 'string' && row.businessKey !== ''
    ? row.businessKey
    : (row.processInstanceId ?? row.compositeId ?? '?')
}

/** The RBAC short/long pair shared by planSelection and planFilterScope (Theme B). */
function roleBlockedOffer(
  rule: { verb: BulkVerbId; label: string; plain: string },
  role: RoleHint,
) {
  return {
    verb: rule.verb,
    label: rule.label,
    plain: rule.plain,
    enabled: false,
    reason: `Requires ${ROLE_FLOOR} — you are ${role}`,
  }
}

/**
 * The strict intersection over the EFFECTIVE selection (protected rows excluded first).
 * Usability round 1, Theme B: any target engine below the RESPONDER floor disables EVERY
 * verb uniformly — greyed the same as the business-logic gates, never regressing the
 * optimistic behavior when the role is unknown (roleHint null).
 */
export function planSelection(
  selected: ProcessInstanceRow[],
  roleHint: RoleHint | null = null,
): SelectionPlan {
  const targets = selected.filter((row) => row.protectedInstance !== true)
  const protectedExcluded = selected.length - targets.length
  const protectionUnknown = targets.filter((row) => row.protectedInstance === undefined).length
  const overCap = targets.length > BULK_CAP

  if (roleHint !== null && !roleAtLeast(roleHint, ROLE_FLOOR)) {
    const offers: BulkVerbOffer[] = RULES.map((rule) => roleBlockedOffer(rule, roleHint))
    return { targets, protectedExcluded, protectionUnknown, overCap, offers }
  }

  const offers: BulkVerbOffer[] = RULES.map((rule) => {
    if (targets.length === 0) {
      const why =
        protectedExcluded > 0 ? 'every selected instance is protected' : 'nothing selected'
      return {
        verb: rule.verb,
        label: rule.label,
        plain: rule.plain,
        enabled: false,
        reason: `Blocked: ${why}`,
        detail: why,
      }
    }
    if (overCap) {
      const deselect = String(targets.length - BULK_CAP)
      return {
        verb: rule.verb,
        label: rule.label,
        plain: rule.plain,
        enabled: false,
        reason: `Blocked: over the ${String(BULK_CAP)}-item cap — deselect ${deselect}`,
        detail: `selection exceeds the ${String(BULK_CAP)}-item cap — deselect ${deselect}`,
      }
    }
    // THE intersection: the first offending row disables the verb for the whole selection.
    for (const row of targets) {
      const blocker = rule.blocker(row)
      if (blocker !== null) {
        return {
          verb: rule.verb,
          label: rule.label,
          plain: rule.plain,
          enabled: false,
          reason: `Blocked: ${rowName(row)} ${blocker.why}`,
          detail: `${rowName(row)} ${blocker.detail}`,
        }
      }
    }
    return { verb: rule.verb, label: rule.label, plain: rule.plain, enabled: true }
  })

  return { targets, protectedExcluded, protectionUnknown, overCap, offers }
}

/**
 * The Intersection Rule over a FILTER scope (v1.x #2): the members are resolved
 * server-side at execution time, so eligibility can only be judged from the status
 * chips — a verb is offered exactly when EVERY status in the filter implies every
 * matching row can accept it. Same doctrine as {@link planSelection}: greyed with the
 * reason, never hidden.
 */
export function planFilterScope(
  statuses: readonly string[] | undefined,
  roleHint: RoleHint | null = null,
): BulkVerbOffer[] {
  if (roleHint !== null && !roleAtLeast(roleHint, ROLE_FLOOR)) {
    return FILTER_RULES.map((rule) => roleBlockedOffer(rule, roleHint))
  }
  const chips = statuses ?? []
  const disable = (
    rule: (typeof FILTER_RULES)[number],
    why: string,
    detail: string,
  ): BulkVerbOffer => ({
    verb: rule.verb,
    label: rule.label,
    plain: rule.plain,
    enabled: false,
    reason: `Blocked: ${why}`,
    detail,
  })
  return FILTER_RULES.map((rule) => {
    if (chips.length === 0) {
      return disable(
        rule,
        'filter has no status chips',
        'the filter needs explicit status chips to scope a bulk action',
      )
    }
    if (chips.includes('COMPLETED')) {
      return disable(
        rule,
        'filter includes COMPLETED instances',
        'the filter includes COMPLETED instances — nothing can act on those',
      )
    }
    const offender = chips.find((chip) => !rule.eligibleStatuses.includes(chip))
    if (offender !== undefined) {
      return disable(
        rule,
        `filter includes ${offender} instances`,
        `the filter includes ${offender} instances, which ${rule.ineligibleWhy}`,
      )
    }
    return { verb: rule.verb, label: rule.label, plain: rule.plain, enabled: true }
  })
}

const FILTER_RULES: {
  verb: BulkVerbId
  label: string
  plain: string
  /** Status chips whose EVERY member can accept the verb. */
  eligibleStatuses: string[]
  ineligibleWhy: string
}[] = [
  {
    verb: 'retry-job',
    label: 'Retry dead-letter jobs',
    plain: 'run the failed steps again',
    eligibleStatuses: ['FAILED'],
    ineligibleWhy: 'have no dead-letter job to retry',
  },
  {
    verb: 'suspend',
    label: 'Suspend',
    plain: 'pause these cases',
    eligibleStatuses: ['ACTIVE', 'FAILED', 'RETRYING'],
    ineligibleWhy: 'are not running (already suspended or ended)',
  },
  {
    verb: 'activate',
    label: 'Activate',
    plain: 'resume these cases',
    eligibleStatuses: ['SUSPENDED'],
    ineligibleWhy: 'are not suspended',
  },
]

/** Per-engine split for the confirm's scope enumeration (SPEC §6 tier-4 spirit). */
export function perEngineSplit(targets: ProcessInstanceRow[]): [string, number][] {
  const counts = new Map<string, number>()
  for (const row of targets) {
    const engine = row.engineId ?? '?'
    counts.set(engine, (counts.get(engine) ?? 0) + 1)
  }
  return [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0]))
}

/**
 * Theme D: the environment band the ticked-row submit modal wears. Mirrors
 * FilterBulkModal's doctrine (prodConfirmToken/enginesInScope) — prod if ANY target's
 * engine is prod, else the first target's engine environment.
 */
export function targetEnvironment(
  targets: ProcessInstanceRow[],
  engines: EngineDto[],
): string | undefined {
  const engineById = new Map(engines.map((engine) => [engine.id, engine]))
  const environments = targets.map((row) => engineById.get(row.engineId)?.environment)
  if (environments.some((env) => env?.toLowerCase() === 'prod')) return 'prod'
  return environments[0]
}

/** Theme H2: the reversibility strip-note shared by all three bulk modals. */
export function reversibilityNote(verb: BulkVerbId): string {
  if (verb === 'retry-job') {
    return (
      'Mostly safe: retrying only re-queues the step. Anything the step does when it runs ' +
      '(emails, payments, calls) is not undone.'
    )
  }
  return 'Reversible — the opposite action undoes it.'
}

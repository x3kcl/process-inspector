// The Intersection Rule (SPEC §7, binding): the bulk bar offers EXACTLY the verbs valid
// for EVERY selected row — one ineligible row disables the verb for the whole selection,
// disabled-with-reason naming the offender (greyed never hidden, SPEC §6). Protected
// instances are auto-excluded from the effective selection and surfaced as a badge.
// Pure and tested; the components render its output verbatim.
import type { ProcessInstanceRow } from '../api/model'

export const BULK_CAP = 200

/** The v1 bulk verb set (queue-state verbs — destructive bulk is the tier-4 wizard). */
export const BULK_VERB_IDS = ['retry-job', 'suspend', 'activate'] as const
export type BulkVerbId = (typeof BULK_VERB_IDS)[number]

export interface BulkVerbOffer {
  verb: BulkVerbId
  label: string
  /** SPEC §5.0 plain-language secondary label. */
  plain: string
  enabled: boolean
  /** Names the gate when disabled: the FIRST offending row, the cap, or emptiness. */
  reason?: string
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

interface VerbRule {
  verb: BulkVerbId
  label: string
  plain: string
  /** null = row is eligible; a string names why THIS row blocks the verb. */
  blocker: (row: ProcessInstanceRow) => string | null
}

const RULES: VerbRule[] = [
  {
    verb: 'retry-job',
    label: 'Retry dead-letter jobs',
    plain: 'run the failed steps again',
    blocker: (row) =>
      row.flags?.hasDeadLetterJobs === true ? null : 'has no dead-letter job (nothing to retry)',
  },
  {
    verb: 'suspend',
    label: 'Suspend',
    plain: 'pause these cases',
    blocker: (row) => {
      if (row.flags?.ended === true || row.status === 'COMPLETED') return 'has ended'
      if (row.flags?.suspended === true || row.status === 'SUSPENDED') return 'is already suspended'
      return null
    },
  },
  {
    verb: 'activate',
    label: 'Activate',
    plain: 'resume these cases',
    blocker: (row) => {
      if (row.flags?.ended === true || row.status === 'COMPLETED') return 'has ended'
      if (!(row.flags?.suspended === true || row.status === 'SUSPENDED')) return 'is not suspended'
      return null
    },
  },
]

function rowName(row: ProcessInstanceRow): string {
  return row.businessKey !== undefined && row.businessKey !== ''
    ? row.businessKey
    : (row.compositeId ?? row.processInstanceId ?? '?')
}

/** The strict intersection over the EFFECTIVE selection (protected rows excluded first). */
export function planSelection(selected: ProcessInstanceRow[]): SelectionPlan {
  const targets = selected.filter((row) => row.protectedInstance !== true)
  const protectedExcluded = selected.length - targets.length
  const protectionUnknown = targets.filter((row) => row.protectedInstance === undefined).length
  const overCap = targets.length > BULK_CAP

  const offers: BulkVerbOffer[] = RULES.map((rule) => {
    if (targets.length === 0) {
      return {
        verb: rule.verb,
        label: rule.label,
        plain: rule.plain,
        enabled: false,
        reason: protectedExcluded > 0 ? 'every selected instance is protected' : 'nothing selected',
      }
    }
    if (overCap) {
      return {
        verb: rule.verb,
        label: rule.label,
        plain: rule.plain,
        enabled: false,
        reason: `selection exceeds the ${String(BULK_CAP)}-item cap — deselect ${String(targets.length - BULK_CAP)}`,
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
          reason: `${rowName(row)} ${blocker}`,
        }
      }
    }
    return { verb: rule.verb, label: rule.label, plain: rule.plain, enabled: true }
  })

  return { targets, protectedExcluded, protectionUnknown, overCap, offers }
}

/** Per-engine split for the confirm's scope enumeration (SPEC §6 tier-4 spirit). */
export function perEngineSplit(targets: ProcessInstanceRow[]): [string, number][] {
  const counts = new Map<string, number>()
  for (const row of targets) {
    const engine = row.engineId ?? '?'
    counts.set(engine, (counts.get(engine) ?? 0) + 1)
  }
  return [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0]))
}

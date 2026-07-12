// U5 (#88): the reason/typed-token guard plumbing every confirm modal repeats — state,
// validation, and the prod token-gate — extracted to one hook so a new modal (or a change to
// the rule) touches one place instead of ~13. Presentational half lives in
// ../components/GuardFields.tsx; the ticket field is a separate, already-extracted concern
// (TicketField.tsx) composed alongside, not folded in — several guarded modals omit it.
import { useState } from 'react'
import type { ReasonRule } from './catalog'

export interface ProdGuardInput {
  /** {required, minLength} — usually `reasonRule(tier, environment)`; pass a literal for
   *  non-verb-tiered actions (admin CRUD, error-group acknowledge) that are always-required. */
  reasonRule: ReasonRule
  environment?: string
  /** The exact string the operator must type to unlock the confirm button. `undefined` = this
   *  action never gates behind a typed token, even on prod (read-only-ish/BFF-only actions). */
  expectedToken?: string
  /** Override WHEN the token field renders — default is "prod AND expectedToken is set". Some
   *  actions (registry remove/purge) demand the token in every environment, not just prod; pass
   *  the action's own predicate here rather than fight the prod-only default. */
  needsToken?: boolean
}

export interface ProdGuard {
  reason: string
  setReason: (value: string) => void
  ticket: string
  setTicket: (value: string) => void
  typed: string
  setTyped: (value: string) => void
  /** True when `environment` is a production engine (case-insensitive). */
  prod: boolean
  /** True when the typed-token field should render at all (prod AND the action has one). */
  needsToken: boolean
  reasonOk: boolean
  /** Vacuously true when `needsToken` is false — never blocks a non-token action. */
  tokenOk: boolean
}

export function useProdGuard({
  reasonRule,
  environment,
  expectedToken,
  needsToken: needsTokenOverride,
}: ProdGuardInput): ProdGuard {
  const [reason, setReason] = useState('')
  const [ticket, setTicket] = useState('')
  const [typed, setTyped] = useState('')

  const prod = environment?.toLowerCase() === 'prod'
  const needsToken = needsTokenOverride ?? (prod && expectedToken !== undefined)
  const trimmed = reason.trim()
  const reasonOk = trimmed === '' ? !reasonRule.required : trimmed.length >= reasonRule.minLength
  const tokenOk = !needsToken || typed === expectedToken

  return {
    reason,
    setReason,
    ticket,
    setTicket,
    typed,
    setTyped,
    prod,
    needsToken,
    reasonOk,
    tokenOk,
  }
}

/**
 * The PROD typed-token gate target (corrective-actions §3): the instance's business key when it
 * has one, else the instance id. Was duplicated verbatim between ChangeStateModal and
 * MigrateModal's step-2 confirm.
 */
export function businessKeyOrInstanceToken(
  businessKey: string | undefined,
  instanceId: string,
): { expectedToken: string; tokenName: string } {
  const hasBusinessKey = businessKey !== undefined && businessKey !== ''
  return {
    expectedToken: hasBusinessKey ? businessKey : instanceId,
    tokenName: hasBusinessKey ? 'business key' : 'instance id',
  }
}

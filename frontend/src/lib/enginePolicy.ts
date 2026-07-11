// Engine policy & lifecycle display helpers (usability W1#4, theme T6).
//
// The registry carries two policy facts the operator must see WHERE actions are offered,
// not only in the ops log: `mode` (read-write | read-only, R-GOV-04 — an engine-OWNER
// contract, never a role verdict) and `lifecycle` (active | disabled | draft | probed |
// probe_failed — a non-active engine is not an operable target). SPEC §10a: the tokens are
// literal text, never color alone; SPEC §6/R-SEM-17: greyed-with-REASON, never hidden.

/** True for the registry read-only mode in ANY spelling — the BFF wire value is the
 *  hyphenated `read-only`; older call sites passed the enum name `READ_ONLY`. */
export function isReadOnlyMode(mode: string | undefined): boolean {
  return mode !== undefined && mode.toUpperCase().replace(/-/g, '_') === 'READ_ONLY'
}

/** True when the engine's lifecycle makes it a non-operable target. An undefined
 *  lifecycle (older BFF) is treated as active — never invent a gate the server never sent. */
export function isInactiveLifecycle(lifecycle: string | undefined): boolean {
  return lifecycle !== undefined && lifecycle !== 'active'
}

/** Plain-language reason per lifecycle state — the dashboard's greyed-with-reason copy.
 *  `disabled` names the engine owner; onboarding states must never impersonate it. */
export function lifecycleGloss(lifecycle: string): string {
  switch (lifecycle) {
    case 'disabled':
      return 'disabled in the registry by the engine owner — not an operable target'
    case 'draft':
      return 'being onboarded (draft) — not yet probed or enabled'
    case 'probed':
      return 'being onboarded (probed, awaiting enable) — not yet enabled'
    case 'probe_failed':
      return 'onboarding probe failed — not enabled'
    case 'removed':
      return 'decommissioned — removed from the registry'
    default:
      return `registry lifecycle "${lifecycle}" — not an active engine`
  }
}

export interface PolicyToken {
  /** The literal badge text (e.g. "READ-ONLY", "DISABLED") — the token IS the meaning. */
  token: string
  /** The hover explanation: who set the policy and what it means for actions. */
  title: string
}

/** The policy tokens to co-render next to the environment badge. Empty for a plain
 *  active read-write engine — the common case stays visually quiet. */
export function enginePolicyTokens(
  mode: string | undefined,
  lifecycle: string | undefined,
): PolicyToken[] {
  const tokens: PolicyToken[] = []
  if (isReadOnlyMode(mode)) {
    tokens.push({
      token: 'READ-ONLY',
      title:
        'Registered read-only — set by the engine owner (R-GOV-04). Every mutating verb is ' +
        'refused on this engine. This is engine policy, not your role.',
    })
  }
  if (lifecycle !== undefined && isInactiveLifecycle(lifecycle)) {
    tokens.push({
      token: lifecycle.toUpperCase().replace(/_/g, ' '),
      title: `This engine is ${lifecycleGloss(lifecycle)}.`,
    })
  }
  return tokens
}

// Pure decision logic for the registry admin UI (docs/REGISTRY-CRUD.md §6/§7) — extracted so the
// state-machine + governance rules are unit-tested without rendering. The BFF is the real gate;
// these functions only decide what the UI OFFERS and which confirms carry a typed token.

export type LifecycleAction = 'enable' | 'disable' | 'remove' | 'purge'
export type Environment = 'DEV' | 'TEST' | 'PROD'

/** The WRITE contract's uppercase enum; the read DTO is lowercase, so map when pre-filling. */
export function toEnvironment(value: string | undefined): Environment {
  const up = (value ?? 'DEV').toUpperCase()
  return up === 'TEST' || up === 'PROD' ? up : 'DEV'
}

export interface RowActions {
  probe: boolean
  edit: boolean
  enable: boolean
  disable: boolean
  remove: boolean
  purge: boolean
}

/** Which actions a row offers for its lifecycle — the earned-trust state machine, UI side. */
export function rowActions(lifecycle: string): RowActions {
  const tombstoned = lifecycle === 'removed'
  return {
    probe: !tombstoned,
    edit: !tombstoned,
    enable: lifecycle === 'probed' || lifecycle === 'disabled',
    disable: lifecycle === 'active',
    remove: lifecycle === 'disabled',
    purge: tombstoned,
  }
}

/**
 * Does this confirm carry a typed token (the engine id)? remove + purge ALWAYS; enable only on a
 * prod read-write flip (mirrors the BFF gate). The DTO environment is lowercase.
 */
export function needsTypedToken(
  action: LifecycleAction,
  environment: string | undefined,
  readWrite: boolean,
): boolean {
  if (action === 'remove' || action === 'purge') return true
  return action === 'enable' && environment === 'prod' && readWrite
}

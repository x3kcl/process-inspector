// The omnibox accepts "a paste of anything" (SPEC §4, R-SEM-04). Two layers:
//   1. classifyOmniboxInput — the client-side fast path: a composite "engine:id" is the
//      inspector's OWN id format and navigates without a round trip;
//   2. decideResolveNavigation — what to do with a GET /api/resolve response. Exactly one
//      ID-kind match navigates; business-key matches become a pre-filtered search (never
//      an auto-navigate, R-SEM-04); anything else is an explicit disambiguation list.
import type { ResolveMatch, ResolveResponse } from '../api/model'

export type OmniboxTarget =
  { kind: 'inspect'; engineId: string; instanceId: string } | { kind: 'resolve'; query: string }

export function classifyOmniboxInput(
  raw: string,
  knownEngineIds: readonly string[],
): OmniboxTarget | null {
  const input = raw.trim()
  if (input === '') return null
  const at = input.indexOf(':')
  if (at > 0) {
    const prefix = input.slice(0, at)
    const rest = input.slice(at + 1).trim()
    // Only a registered engine id makes the prefix a composite ID — "order:4711" where
    // "order" is no engine stays an opaque resolver query.
    if (rest !== '' && knownEngineIds.includes(prefix)) {
      return { kind: 'inspect', engineId: prefix, instanceId: rest }
    }
  }
  return { kind: 'resolve', query: input }
}

export type ResolveDecision =
  | { kind: 'navigate'; engineId: string; processInstanceId: string }
  | { kind: 'search-business-key'; businessKey: string }
  | { kind: 'disambiguate'; matches: ResolveMatch[] }
  | { kind: 'not-found' }

export function decideResolveNavigation(response: ResolveResponse): ResolveDecision {
  const matches = response.matches ?? []
  if (matches.length === 0) return { kind: 'not-found' }

  // Business-key hits route to a PRE-FILTERED SEARCH: a key legitimately names many
  // instances, so the grid (not a guess) is the right landing (R-SEM-04).
  if (matches.every((match) => match.kind === 'BUSINESS_KEY')) {
    return {
      kind: 'search-business-key',
      businessKey: matches[0].businessKey ?? response.query ?? '',
    }
  }

  // Exactly ONE exact (ID-kind) match — open Stage 2 directly.
  if (matches.length === 1) {
    const match = matches[0]
    if (match.engineId !== undefined && match.processInstanceId !== undefined) {
      return {
        kind: 'navigate',
        engineId: match.engineId,
        processInstanceId: match.processInstanceId,
      }
    }
  }

  // Several engines matched (ids are only engine-unique) or kinds are mixed —
  // the operator picks; never an auto-navigate on ambiguity.
  return { kind: 'disambiguate', matches }
}

/** "resolved against N of M engines" + who is down — the honesty line (R-SEM-12). */
export interface ReachabilitySummary {
  reached: number
  total: number
  unreachable: { engineId: string; error: string }[]
}

export function summarizeReachability(response: ResolveResponse): ReachabilitySummary {
  const probes = Object.entries(response.perEngine ?? {})
  const unreachable = probes
    .filter(([, probe]) => probe.ok !== true)
    .map(([engineId, probe]) => ({ engineId, error: probe.error ?? 'unreachable' }))
  return { reached: probes.length - unreachable.length, total: probes.length, unreachable }
}

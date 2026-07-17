// Coverage-claim honesty (#236): a sentence that claims completeness over "N engines"
// ("resolved against 3 of 3 engines", "confirmed zero across 3 engines", "clean on every
// reachable engine") must count and name — IN THE SAME SENTENCE — the registered engines
// that were never part of that check. Before this, registered-but-excluded engines
// (non-active lifecycle, unreachable, scope-excluded) only surfaced in the separate
// Engines panel, so a reader could not tell from the sentence alone that the check was
// not exhaustive; a wrong "does not exist" answer closes a real customer's ticket.
import type { EngineDto } from '../api/model'
import { formatCount } from './format'

export interface UncoveredEngine {
  id: string
  /** Plain gloss for WHY the engine sat out — '' when the registry view offers no reason. */
  reason: string
}

/**
 * The registered (display-list) engines a check never covered: everything in `registered`
 * whose id is absent from `coveredEngineIds`. What "covered" means is the caller's choice —
 * the fan-out envelope's per-engine keys for "this engine was probed at all", or only the
 * ok keys when the sentence claims cleanliness of the engines that answered.
 */
export function uncoveredEngines(
  registered: readonly EngineDto[] | undefined,
  coveredEngineIds: Iterable<string>,
): UncoveredEngine[] {
  const covered = new Set(coveredEngineIds)
  const uncovered: UncoveredEngine[] = []
  for (const engine of registered ?? []) {
    if (engine.id === undefined || engine.id === '' || covered.has(engine.id)) continue
    uncovered.push({ id: engine.id, reason: uncoveredReason(engine) })
  }
  return uncovered
}

/** Why the registry/health view says this engine sat out — non-active lifecycle first. */
function uncoveredReason(engine: EngineDto): string {
  const lifecycle = engine.lifecycle ?? 'active'
  // Non-active registry lifecycle (disabled / draft / probe_failed): the fan-out never
  // targets these, so they are invisible to every per-engine envelope.
  if (lifecycle !== 'active') return lifecycle.replaceAll('_', ' ')
  if (engine.reachable === false) return 'currently unreachable'
  return ''
}

/**
 * The inline disclosure clause, with a leading space so it splices before the sentence's
 * final period. '' when the check covered every registered engine — a genuinely complete
 * check must never wear a spurious "more excluded" tail. Example:
 * " (2 more registered engines not searched: eng-c [disabled], eng-d [currently unreachable] — see Engines)"
 */
export function uncoveredClause(uncovered: readonly UncoveredEngine[], participle: string): string {
  if (uncovered.length === 0) return ''
  const names = uncovered
    .map((engine) => (engine.reason === '' ? engine.id : `${engine.id} [${engine.reason}]`))
    .join(', ')
  const noun = uncovered.length === 1 ? 'engine' : 'engines'
  return ` (${formatCount(uncovered.length)} more registered ${noun} not ${participle}: ${names} — see Engines)`
}

// Verb-aware outcome labeling (usability round 1, Theme G): a "successful" retry-job item
// has NOT succeeded — the failed step has only been re-queued, and it can fail again on
// its next attempt. Every other outcome keeps its existing label unchanged.
export function outcomeLabel(verb: string | undefined, state: string | undefined): string {
  if (state === 'ok') return verb === 'retry-job' ? 're-queued' : 'done'
  if (state === 'skipped_protected') return 'skipped (protected)'
  if (state === 'not_run') return 'not run'
  return state ?? ''
}

/** The per-item Outcome cell's CSS modifier — re-queued gets its OWN amber class, never
 *  the green "ok" family (it has not succeeded yet). */
export function outcomeClassName(verb: string | undefined, state: string | undefined): string {
  if (state === 'ok' && verb === 'retry-job') return 'outcome-requeued'
  return `outcome-${(state ?? '').replace('_', '-')}`
}

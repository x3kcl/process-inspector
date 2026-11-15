// #662: the per-episode corrective-action tally (#358 item 2, EpisodeActionAttributionService)
// rendered on the incident's episode row. The join is engine + time, so the line always rides
// with EPISODE_ACTIONS_CAVEAT (RETRYING-RISK-LANE.md §3.3/§10: it can over-include).
import type { components } from '../api/schema'

type Attribution = components['schemas']['EpisodeActionAttribution']

export const EPISODE_ACTIONS_CAVEAT =
  'matched by engine and time, so it can include unrelated actions on the same engine'

function tally(counts: Record<string, number> | undefined, suffix: (n: number) => string) {
  return Object.entries(counts ?? {})
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1])
    .map(([key, n]) => `${key} ${suffix(n)}`)
}

export function episodeActionsLine(attr: Attribution | undefined): string {
  if (attr === undefined) return ''
  const count = attr.count ?? 0
  if (count === 0) return 'no actions during this episode'
  const parts = [
    ...tally(attr.byVerb, (n) => `×${String(n)}`),
    ...tally(attr.byOutcome, (n) => String(n)),
  ]
  const head = `${attr.truncated === true ? '≥ ' : ''}${String(count)} action${count === 1 ? '' : 's'}`
  return `${head} during this episode${parts.length > 0 ? ': ' + parts.join(' · ') : ''}`
}

// The Ops log bounded to the episode window, and to its engine when the class has only one
// (the log filters on a single engine; a multi-engine class keeps every engine).
export function episodeOpsLogHref(
  episode: { startedAt?: string; endedAt?: string },
  engineIds: string[],
): string {
  if (episode.startedAt === undefined) return '/audit'
  const query = new URLSearchParams({ since: episode.startedAt })
  if (episode.endedAt !== undefined) query.set('until', episode.endedAt)
  if (engineIds.length === 1) query.set('engineId', engineIds[0])
  return `/audit?${query.toString()}`
}

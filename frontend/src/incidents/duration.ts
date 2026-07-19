// Per-episode duration display (INCIDENT-LEDGER.md §3.2/§8) — the MTTR substrate's one
// derived label. An episode with no `endedAt` is the LIVE one (at most one per incident);
// its duration is deliberately never computed client-side from `startedAt` vs "now" (that
// would need a ticking clock for a value the ledger doesn't actually track) — "ongoing" is
// the honest label. A closed episode's duration comes straight from the server's own
// `durationSeconds`, reusing the app's one shared formatter (lib/format.ts) rather than a
// parallel one.
import { formatSeconds } from '../lib/format'

export interface EpisodeDurationInput {
  endedAt?: string
  durationSeconds?: number
}

export function episodeDurationLabel(episode: EpisodeDurationInput): string {
  if (episode.endedAt === undefined || episode.endedAt === '') return 'ongoing'
  return formatSeconds(episode.durationSeconds ?? 0)
}

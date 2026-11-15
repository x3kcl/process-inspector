import type { components } from '../api/schema'

type Attribution = components['schemas']['EpisodeActionAttribution']

/** #662 stub — RED. */
export function episodeActionsLine(_attr: Attribution | undefined): string {
  return ''
}

/** #662 stub — RED. */
export function episodeOpsLogHref(_startedAt: string | undefined): string {
  return '/audit'
}

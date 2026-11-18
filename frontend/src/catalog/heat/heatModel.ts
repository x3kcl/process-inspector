// Pure helpers for the definition Heat tab (#785, DEFINITION-HEATMAP.md §5). Colour is never the
// only carrier: the figure, the tier word and the border style all say it.
import type { components } from '../../api/schema'
import type { GlyphTexts } from '../../inspect/diagram/markerKinds'
import { formatCount, formatDateTime, formatDurationMs } from '../../lib/format'

export type Heat = components['schemas']['DefinitionHeat']
export type NodeHeat = components['schemas']['NodeHeat']

/** Presets only: every distinct N is a separate server-side gather (default 200, max 1000). */
export const HEAT_PRESETS: readonly number[] = [200, 500, 1000]

const TIER_KEY = {
  DOMINANT: 'heatDominantActivityIds',
  WARM: 'heatWarmActivityIds',
  COOL: 'heatCoolActivityIds',
} as const
// Tasks, sub-processes and call activities carry a badge; events and gateways measure ≈ 0 ms.
const BADGED = /Task$|^(subProcess|callActivity|transaction|adHocSubProcess)$/

const pct = (value: number | undefined) => `${String(Number((value ?? 0).toFixed(1)))} %`

export function badgeText(node: NodeHeat): string {
  const parts: string[] = []
  if ((node.executions ?? 0) > 0) parts.push(`avg ${formatDurationMs(node.avgMs ?? 0)}`)
  parts.push(pct(node.hitPct))
  const tier = node.tier ?? 'NONE'
  if (tier !== 'NONE') parts.push(tier.toLowerCase())
  const armed = node.armedNotTaken ?? 0
  if (armed > 0) {
    const taken = node.instances ?? 0
    parts.push(`armed in ${String(taken + armed)}, taken in ${String(taken)}`)
  }
  return parts.join(' · ')
}

export interface HeatMarkers {
  sets: Record<string, string[]>
  texts: GlyphTexts
}

export function heatMarkers(nodes: NodeHeat[]): HeatMarkers {
  const sets: Record<string, string[]> = {
    heatDominantActivityIds: [],
    heatWarmActivityIds: [],
    heatCoolActivityIds: [],
  }
  const texts: GlyphTexts = {}
  for (const node of nodes) {
    const id = node.activityId
    if (id === undefined) continue
    if (!BADGED.test(node.type ?? '') && (node.armedNotTaken ?? 0) === 0) continue
    const tier = node.tier ?? 'NONE'
    const key = tier === 'DOMINANT' || tier === 'WARM' ? TIER_KEY[tier] : TIER_KEY.COOL
    sets[key].push(id)
    const byId = texts[key] ?? new Map<string, string>()
    byId.set(id, badgeText(node))
    texts[key] = byId
  }
  return { sets, texts }
}

/** Everything the viewer must be told about how far the figures can be trusted. */
export function honestyLines(heat: Heat): string[] {
  const lines = [
    `Heat is a sample: sampled ${formatCount(heat.sampledInstances ?? 0)} of ${formatCount(heat.finishedTotal ?? 0)} finished instances, data as of ${formatDateTime(heat.asOf)}${heat.cached === true ? ' (cached)' : ''}.`,
  ]
  const dropped = heat.droppedInstances ?? 0
  if (dropped > 0) {
    const why = heat.dropReasons
    const reasons = [
      [why?.tooManyRows, 'too many activity rows'],
      [why?.readFailed, 'unreadable'],
      [why?.noActivityHistory, 'without activity history'],
    ]
      .filter(([count]) => ((count as number | undefined) ?? 0) > 0)
      .map(([count, text]) => `${String(count)} ${String(text)}`)
    lines.push(`${String(dropped)} dropped: ${reasons.join(', ')}.`)
  }
  if (heat.coverage === 'NO_FLOW_HISTORY') {
    lines.push('This engine records no route, so the path between steps is not shown.')
    if (heat.samplingNote?.includes('inferred') === true)
      lines.push('Where an event gateway forks, event-gateway branches are inferred from timing.')
  }
  if (heat.structure === 'UNAVAILABLE')
    lines.push(
      'The model could not be read, so armed and taken events could not be told apart; event-gateway branches are counted as taken.',
    )
  if (heat.coverage === 'NO_ACTIVITY_HISTORY')
    lines.push('This version records no activity history, so there is nothing to measure.')
  if (heat.coverage === 'NO_HISTORY')
    lines.push('No finished instances yet for this version. Heat appears once one finishes.')
  return lines
}

const MESSAGES: Record<string, string> = {
  'definition-not-found': 'This definition version no longer exists on this engine.',
  'heat-bpmn-only': 'Heat covers BPMN process definitions only. This is a case definition.',
  'engine-unavailable':
    'The engine did not answer, so no heat can be shown. Try again when it is reachable.',
  'heat-unavailable':
    'The engine answered, but the history of the sampled instances could not be read, so no figure can be shown.',
}

export function refusalMessage(error: unknown): string {
  const body = (error as { body?: { code?: unknown } } | null)?.body
  const code = typeof body?.code === 'string' ? body.code : ''
  return MESSAGES[code] ?? (error instanceof Error ? error.message : 'Heat could not be loaded.')
}

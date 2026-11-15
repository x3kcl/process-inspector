import type { components } from '../../api/schema'

type InstanceTimeline = components['schemas']['InstanceTimeline']

/**
 * The execution overlay (SPEC §4 Stage 2, §10a): what the instance's history says about the
 * model — the route taken, which nodes completed, which were armed but never continued (the
 * event-based-gateway losers), and where the time went. Pure: timeline rows + BPMN XML in.
 */
export interface ExecutionOverlay {
  /** Sequence-flow id → how often it was taken. */
  takenFlows: Map<string, number>
  completed: string[]
  armedNotTaken: string[]
  /** Activity id → summed duration (rows under 1 ms left out). */
  durations: Map<string, number>
  /** The slowest badged node — only when at least two nodes carry a badge. */
  slowestId?: string
  /** The timeline was truncated or capped: the route shown may stop early. */
  partial: boolean
}

/** Source activity id → its outgoing sequence-flow ids; null when the XML cannot be read. */
function outgoingFlows(xml: string | undefined): Map<string, string[]> | null {
  if (xml === undefined || xml === '') return null
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length > 0) return null
  const out = new Map<string, string[]>()
  for (const flow of Array.from(doc.getElementsByTagNameNS('*', 'sequenceFlow'))) {
    const source = flow.getAttribute('sourceRef')
    const id = flow.getAttribute('id')
    if (source === null || id === null) continue
    out.set(source, [...(out.get(source) ?? []), id])
  }
  return out
}

export function executionOverlay(
  timeline: InstanceTimeline,
  xml: string | undefined,
  activeIds: string[],
): ExecutionOverlay {
  const rows = timeline.activities ?? []
  const takenFlows = new Map<string, number>()
  const durations = new Map<string, number>()
  const ended = new Map<string, string | undefined>()
  for (const row of rows) {
    const id = row.activityId
    if (id === undefined) continue
    if (row.activityType === 'sequenceFlow') {
      takenFlows.set(id, (takenFlows.get(id) ?? 0) + 1)
      continue
    }
    if (row.endTime !== undefined) ended.set(id, row.activityType)
    if (row.durationMs !== undefined && row.durationMs >= 1) {
      durations.set(id, (durations.get(id) ?? 0) + row.durationMs)
    }
  }

  const outgoing = outgoingFlows(xml)
  const active = new Set(activeIds)
  const completed: string[] = []
  const armedNotTaken: string[] = []
  for (const [id, type] of ended) {
    if (active.has(id)) continue
    const outs = outgoing?.get(id) ?? []
    const continued =
      outgoing === null ||
      type === 'endEvent' ||
      outs.length === 0 ||
      outs.some((flow) => takenFlows.has(flow))
    ;(continued ? completed : armedNotTaken).push(id)
  }

  let slowestId: string | undefined
  if (durations.size >= 2) {
    for (const [id, ms] of durations) {
      if (slowestId === undefined || ms > (durations.get(slowestId) ?? 0)) slowestId = id
    }
  }

  return {
    takenFlows,
    completed,
    armedNotTaken,
    durations,
    slowestId,
    partial: timeline.truncated === true || rows.some((row) => row.isCapped === true),
  }
}

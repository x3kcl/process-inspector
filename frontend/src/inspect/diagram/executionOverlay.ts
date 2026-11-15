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

export function executionOverlay(
  _timeline: InstanceTimeline,
  _xml: string | undefined,
  _activeIds: string[],
): ExecutionOverlay {
  return {
    takenFlows: new Map(),
    completed: [],
    armedNotTaken: [],
    durations: new Map(),
    partial: false,
  }
}

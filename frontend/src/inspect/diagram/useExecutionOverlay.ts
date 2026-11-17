import { useMemo } from 'react'
import { useInstanceTimeline } from '../useInstanceQueries'
import { executionOverlay } from './executionOverlay'
import type { ExecutionOverlay } from './executionOverlay'

/**
 * The execution overlay for an instance diagram (#677), fed by the timeline query. Pending or
 * failed timeline → undefined, and the diagram stays exactly as without it.
 */
export function useExecutionOverlay(
  engineId: string,
  instanceId: string,
  xml: string | undefined,
  activeIds: string[],
): ExecutionOverlay | undefined {
  const timeline = useInstanceTimeline(engineId, instanceId)
  return useMemo(
    () =>
      timeline.data === undefined ? undefined : executionOverlay(timeline.data, xml, activeIds),
    [timeline.data, xml, activeIds],
  )
}

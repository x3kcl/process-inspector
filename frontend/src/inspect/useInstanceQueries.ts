// Stage 2 data hooks: one TanStack Query per detail endpoint, keyed per instance segment.
// LAZY BY CONSTRUCTION (SPEC §4): each tab component owns its hook, and tabs are
// lazy-mounted — a segment is fetched the first time its tab is opened, never before.
// staleTime keeps tab-switching snappy without hammering the engines mid-incident.
import { useQuery } from '@tanstack/react-query'
import {
  fetchInstanceDiagram,
  fetchInstanceStatusEvidence,
  fetchInstanceExternalWorkerJobs,
  fetchInstanceHierarchy,
  fetchInstanceJobs,
  fetchInstanceTasks,
  fetchInstanceTimeline,
  fetchInstanceVariables,
  fetchInstanceVitals,
  fetchNearestSibling,
  fetchSiblingDiff,
} from '../api/queries'

const STALE_MS = 15_000

function key(engineId: string, instanceId: string, segment: string) {
  return ['instance', engineId, instanceId, segment]
}

export function useInstanceVitals(engineId: string, instanceId: string) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'vitals'),
    queryFn: () => fetchInstanceVitals({ engineId, instanceId }),
    staleTime: STALE_MS,
  })
}

/**
 * "Explain this status" evidence (R-L3-01) — re-derived on demand, so it is NEVER cached
 * stale: staleTime 0 and the freshly-stamped `rederivedAt` is the honesty label. Enabled only
 * when the modal is open (the caller passes `enabled`), so opening a chip is what fires it.
 */
export function useInstanceStatusEvidence(engineId: string, instanceId: string, enabled: boolean) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'explain-status'),
    queryFn: () => fetchInstanceStatusEvidence({ engineId, instanceId }),
    enabled,
    staleTime: 0,
    gcTime: 0,
  })
}

export function useInstanceDiagram(engineId: string, instanceId: string) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'diagram'),
    queryFn: () => fetchInstanceDiagram({ engineId, instanceId }),
    // The XML is immutable per deployment; only the marker sets move.
    staleTime: STALE_MS,
  })
}

export function useInstanceVariables(engineId: string, instanceId: string) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'variables'),
    queryFn: () => fetchInstanceVariables({ engineId, instanceId }),
    staleTime: STALE_MS,
  })
}

export function useInstanceJobs(engineId: string, instanceId: string) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'jobs'),
    queryFn: () => fetchInstanceJobs({ engineId, instanceId }),
    staleTime: STALE_MS,
  })
}

/**
 * External-worker jobs — the fifth queue (v1.x #7). CAPABILITY-GATED at the source: the caller
 * passes `enabled` from `EngineDto.capabilities.externalWorkerJobs`, so on a pre-6.8 engine the
 * query never fires (no empty lane, no spinner) and the BFF is never called. The BFF stays the
 * real gate and refuses if hit anyway.
 */
export function useInstanceExternalWorkerJobs(
  engineId: string,
  instanceId: string,
  enabled: boolean,
) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'external-worker-jobs'),
    queryFn: () => fetchInstanceExternalWorkerJobs({ engineId, instanceId }),
    enabled,
    staleTime: STALE_MS,
  })
}

export function useInstanceTasks(engineId: string, instanceId: string) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'tasks'),
    queryFn: () => fetchInstanceTasks({ engineId, instanceId }),
    staleTime: STALE_MS,
  })
}

export function useInstanceHierarchy(engineId: string, instanceId: string) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'hierarchy'),
    queryFn: () => fetchInstanceHierarchy({ engineId, instanceId }),
    staleTime: STALE_MS,
  })
}

export function useInstanceTimeline(engineId: string, instanceId: string) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'timeline'),
    queryFn: () => fetchInstanceTimeline({ engineId, instanceId }),
    staleTime: STALE_MS,
  })
}

/**
 * The sibling-diff smart default (SPEC §5.2). Historic-only and stable per instance, so it
 * caches longer than the live tabs — the "most recent completed run" barely moves mid-triage.
 */
export function useNearestSibling(engineId: string, instanceId: string) {
  return useQuery({
    queryKey: key(engineId, instanceId, 'nearest-sibling'),
    queryFn: () => fetchNearestSibling({ engineId, instanceId }),
    staleTime: 60_000,
  })
}

/** The three-way diff against {@code siblingId}; disabled until a sibling is chosen. */
export function useSiblingDiff(
  engineId: string,
  instanceId: string,
  siblingId: string | undefined,
) {
  return useQuery({
    queryKey: [...key(engineId, instanceId, 'diff'), siblingId],
    queryFn: () => fetchSiblingDiff({ engineId, instanceId }, siblingId ?? ''),
    enabled: siblingId !== undefined && siblingId !== '',
    staleTime: STALE_MS,
  })
}

// Type aliases over the GENERATED contract (src/api/schema.d.ts, `npm run gen:api`).
// Never widen or hand-extend these — regenerate after backend DTO changes (R-SEM-15).
import type { components } from './schema'

export type EngineDto = components['schemas']['EngineDto']
export type JobLanes = components['schemas']['JobLanes']
export type SearchRequest = components['schemas']['SearchRequest']
export type SearchResponse = components['schemas']['SearchResponse']
export type ProcessInstanceRow = components['schemas']['ProcessInstanceRow']
export type InstanceStatusFlags = components['schemas']['InstanceStatusFlags']
export type EngineResult = components['schemas']['EngineResult']
export type VariableFilter = components['schemas']['VariableFilter']
export type TriageDashboardResponse = components['schemas']['TriageDashboardResponse']
export type ErrorGroup = components['schemas']['ErrorGroup']
export type PerEngineTriage = components['schemas']['PerEngineTriage']
export type AuditEntryDto = components['schemas']['AuditEntryDto']
export type NoteDto = components['schemas']['NoteDto']

// Stage 2 detail contract (M3)
export type InstanceDetail = components['schemas']['InstanceDetail']
export type CurrentActivity = components['schemas']['CurrentActivity']
export type WhyStuck = components['schemas']['WhyStuck']
export type WaitState = components['schemas']['WaitState']
export type InstanceDiagram = components['schemas']['InstanceDiagram']
export type InstanceVariables = components['schemas']['InstanceVariables']
export type VariableDto = components['schemas']['VariableDto']
export type ExecutionScope = components['schemas']['ExecutionScope']
export type InstanceJobs = components['schemas']['InstanceJobs']
export type JobDto = components['schemas']['JobDto']
export type InstanceTasks = components['schemas']['InstanceTasks']
export type TaskDto = components['schemas']['TaskDto']
export type InstanceHierarchy = components['schemas']['InstanceHierarchy']
export type HierarchyNode = components['schemas']['HierarchyNode']
export type InstanceTimeline = components['schemas']['InstanceTimeline']
export type TimelineActivity = components['schemas']['TimelineActivity']
export type ResolveResponse = components['schemas']['ResolveResponse']
export type ResolveMatch = components['schemas']['ResolveMatch']
export type EngineProbe = components['schemas']['EngineProbe']

// Sibling diff (v1.x #5, SPEC §5.2)
export type NearestSiblingResponse = components['schemas']['NearestSiblingResponse']
export type SiblingRef = components['schemas']['SiblingRef']
export type SiblingDiffResponse = components['schemas']['SiblingDiffResponse']
export type SiblingInstanceRef = components['schemas']['InstanceRef']
export type VariableDelta = components['schemas']['VariableDelta']
export type VariableChange = NonNullable<VariableDelta['change']>
export type PathDivergence = components['schemas']['PathDivergence']
export type PathActivity = components['schemas']['PathActivity']
export type TimingDelta = components['schemas']['TimingDelta']

/** The four engine job queues, in render order: the diagnosis lane first (SPEC §4). */
export const JOB_LANES = ['deadLetter', 'executable', 'timer', 'suspended'] as const
export type JobLaneId = (typeof JOB_LANES)[number]

export type InstanceStatus = NonNullable<ProcessInstanceRow['status']>

export const ALL_STATUSES: readonly InstanceStatus[] = [
  'ACTIVE',
  'SUSPENDED',
  'COMPLETED',
  'FAILED',
  'RETRYING',
]

export function isInstanceStatus(value: string): value is InstanceStatus {
  return (ALL_STATUSES as readonly string[]).includes(value)
}

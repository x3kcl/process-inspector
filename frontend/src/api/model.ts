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
// Stage-0 leak views (R-BAU-02): per-definition long-running/long-suspended instance counts.
export type LeakViewsResponse = components['schemas']['LeakViewsResponse']
export type LeakDefinitionCount = components['schemas']['LeakDefinitionCount']
export type LeakWindows = components['schemas']['LeakWindows']
export type ErrorGroup = components['schemas']['ErrorGroup']
export type ErrorGroupAcknowledgement = components['schemas']['ErrorGroupAcknowledgement']
export type PerEngineTriage = components['schemas']['PerEngineTriage']
export type OutOfScopeDeadLetters = components['schemas']['OutOfScopeDeadLetters']
// v2/M4 job-lane trend store (R-BAU-08) — the Stage-0 sparkline series.
export type TriageTrendResponse = components['schemas']['TriageTrendResponse']
export type TrendSeries = components['schemas']['Series']
export type TrendPoint = components['schemas']['Point']
// v2/M4 server-backed Saved Views + Recent Searches (SPEC §8) — replace the v1 localStorage stores.
export type SavedViewDto = components['schemas']['SavedViewDto']
export type RecentSearchDto = components['schemas']['RecentSearchDto']
export type TeamViewDto = components['schemas']['TeamViewDto']
export type CmmnDeadLetterJob = components['schemas']['CmmnDeadLetterJob']
export type CmmnScopeFacet = components['schemas']['CmmnScopeFacet']
export type CmmnLaneCounts = components['schemas']['CmmnLaneCounts']

// Case Inspector Phase 2 — polymorphic Stage-2 CMMN case detail (cmmn-js + plan-item timeline)
export type CaseDetail = components['schemas']['CaseDetail']
export type CaseFailing = components['schemas']['CaseFailing']
export type CaseDiagram = components['schemas']['CaseDiagram']
export type CasePlanItems = components['schemas']['CasePlanItems']
export type CasePlanItem = components['schemas']['CasePlanItem']
// springdoc inlines the enum on the field rather than as a named schema, so derive it.
export type CmmnLiveJobState = NonNullable<CasePlanItem['liveJobState']>
export type AuditEntryDto = components['schemas']['AuditEntryDto']
export type NoteDto = components['schemas']['NoteDto']

// Stage 2 detail contract (M3)
export type InstanceDetail = components['schemas']['InstanceDetail']
export type StatusEvidence = components['schemas']['StatusEvidence']
export type StatusEvidenceLeg = components['schemas']['Leg']
export type StatusEvidenceFinding = components['schemas']['FlagFinding']
export type CurrentActivity = components['schemas']['CurrentActivity']
export type WhyStuck = components['schemas']['WhyStuck']
export type WaitState = components['schemas']['WaitState']
export type InstanceDiagram = components['schemas']['InstanceDiagram']
export type InstanceVariables = components['schemas']['InstanceVariables']
export type VariableDto = components['schemas']['VariableDto']
export type ExecutionScope = components['schemas']['ExecutionScope']
export type InstanceJobs = components['schemas']['InstanceJobs']
export type ExternalWorkerJobDto = components['schemas']['ExternalWorkerJobDto']
export type JobDto = components['schemas']['JobDto']
export type InstanceTasks = components['schemas']['InstanceTasks']
export type TaskDto = components['schemas']['TaskDto']
// Person-centric task search (#99) — "what is this person sitting on across engines".
export type PersonTaskSearchResponse = components['schemas']['PersonTaskSearchResponse']
export type PersonTaskRow = components['schemas']['PersonTaskRow']
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

/**
 * The CMMN case lane set (Case Inspector Phase 1, R-SEM-20) — deliberately SEPARATE from the
 * BPMN {@link ALL_STATUSES}. A CMMN case cannot be SUSPENDED (spike Q2), and it CAN be
 * TERMINATED — the mirror image of the BPMN set, which hardcodes SUSPENDED and has no
 * TERMINATED. Any polymorphic scope-typed UI must drive its lanes off THIS const, never reuse
 * ALL_STATUSES, or TERMINATED silently vanishes and an always-empty SUSPENDED lane appears
 * (docs/CMMN-SCOPE-PHASE-0.md §7 M4 hazard).
 */
export const CMMN_STATUSES = ['ACTIVE', 'FAILED', 'COMPLETED', 'TERMINATED'] as const
export type CmmnStatus = (typeof CMMN_STATUSES)[number]

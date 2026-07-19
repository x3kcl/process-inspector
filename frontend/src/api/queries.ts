import { api, ApiError } from './client'
import type {
  AuditEntryDto,
  EngineDto,
  InstanceDetail,
  InstanceDiagram,
  InstanceHierarchy,
  ExternalWorkerJobDto,
  InstanceJobs,
  InstanceTasks,
  InstanceTimeline,
  InstanceVariables,
  LeakViewsResponse,
  NearestSiblingResponse,
  CmmnScopeFacet,
  CaseDetail,
  CaseDiagram,
  CasePlanItems,
  IncidentDetail,
  IncidentSummary,
  NoteDto,
  OutOfScopeDeadLetters,
  PersonTaskSearchResponse,
  ResolveResponse,
  SearchRequest,
  SearchResponse,
  SiblingDiffResponse,
  StatusEvidence,
  TriageDashboardResponse,
  TriageTrendResponse,
  SavedViewDto,
  RecentSearchDto,
  TeamViewDto,
  VariableDto,
} from './model'

export async function fetchEngines(): Promise<EngineDto[]> {
  const { data, error, response } = await api.GET('/api/engines')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function runSearch(request: SearchRequest): Promise<SearchResponse> {
  const { data, error, response } = await api.POST('/api/search', { body: request })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * Person-centric task search (#99): every OPEN task assigned to, or claimable by, `person`
 * across every readable engine. `engineIds` narrows the fan-out (undefined = every engine).
 */
export async function runPersonTaskSearch(
  person: string,
  engineIds?: string[],
): Promise<PersonTaskSearchResponse> {
  const { data, error, response } = await api.GET('/api/tasks', {
    params: { query: { person, engineIds } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Stage 0 aggregations; refresh=true bypasses the BFF's 20s cache (rate-limited). */
export async function fetchTriage(refresh: boolean): Promise<TriageDashboardResponse> {
  const { data, error, response } = await api.GET('/api/triage', {
    params: { query: refresh ? { refresh: true } : {} },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * v2/M4 job-lane trend history (R-BAU-08) for the Stage-0 sparklines — read from the snapshot
 * store, never the live engine. Look-back in hours (server-clamped to 30 days).
 */
export async function fetchTriageTrends(hours: number): Promise<TriageTrendResponse> {
  const { data, error, response } = await api.GET('/api/triage/trends', {
    params: { query: { hours } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * Stage-0 leak views (SPEC §4, R-BAU-02): per-definition counts of long-running and
 * long-suspended instances, from count-only queries. Age = now − startTime for every window,
 * SUSPENDED included (R-SEM-05 — there is no suspension timestamp). Cached at the BFF; a down
 * engine degrades to a named lower bound, never a failed response, so this is always a 200.
 */
export async function fetchLeakViews(): Promise<LeakViewsResponse> {
  const { data, error, response } = await api.GET('/api/triage/leak-views')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * The Incident Ledger list (R-BAU-10, docs/INCIDENT-LEDGER.md §6): VIEWER floor, unpaginated
 * (bounded by distinct failure classes), scope-projected server-side. `state` filters
 * case-insensitively (the BFF 400s on an unknown value); `windowHours` keeps only incidents
 * last seen inside the window (absent = the whole ledger, clamped like `/api/triage/trends`).
 */
export async function fetchIncidents(
  state?: string,
  windowHours?: number,
): Promise<IncidentSummary[]> {
  const { data, error, response } = await api.GET('/api/incidents', {
    params: { query: { state, window: windowHours } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * One incident's full detail: the ledger row, its episode history, the windowed occurrence
 * series (for the timeline chart) and the live Stage-0 `ErrorGroup` join (read-only ack
 * state, "Retry group" coordinates). Unknown or out-of-scope id ⇒ 404 — never leaked as a 403.
 */
export async function fetchIncident(id: number, windowHours?: number): Promise<IncidentDetail> {
  const { data, error, response } = await api.GET('/api/incidents/{id}', {
    params: { path: { id }, query: { window: windowHours } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/* ---- v2/M4 server-backed Saved Views + Recent Searches (SPEC §8), keyed to the caller ---- */

export async function fetchSavedViews(): Promise<SavedViewDto[]> {
  const { data, error, response } = await api.GET('/api/views')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Upsert by name (re-saving a name replaces it) — mirrors the v1 store's semantics. */
export async function putSavedView(name: string, search: string): Promise<SavedViewDto> {
  const { data, error, response } = await api.PUT('/api/views', { body: { name, search } })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function deleteSavedView(id: number): Promise<void> {
  const { error, response } = await api.DELETE('/api/views/{id}', { params: { path: { id } } })
  if (!response.ok) throw new ApiError(response.status, error)
}

/* ---------------- team (shared) views (v2, SHARED-VIEWS.md) ---------------- */

/** The caller's visible team canon (overlaps()-filtered server-side; a DECLUTTER list, not a gate). */
export async function fetchTeamViews(): Promise<TeamViewDto[]> {
  const { data, error, response } = await api.GET('/api/team-views')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * Publish a private view's snapshot as team canon. The scope is DERIVED server-side from the search
 * (never sent from here); the server gates on covers() and refuses (403/400/409) — surfaced as an
 * {@link ApiError} the caller renders inline.
 */
export async function publishTeamView(body: {
  name: string
  search: string
  description?: string
  runbookUrl?: string
}): Promise<TeamViewDto> {
  const { data, error, response } = await api.POST('/api/team-views', { body })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Unpublish (remove from team canon). Reason ≥10 REQUIRED for every caller — a moderation verb
 *  (W2 #3, R-SAFE-16); the server enforces the floor and renders it in the operations log. */
export async function unpublishTeamView(id: number, reason: string): Promise<void> {
  const { error, response } = await api.POST('/api/team-views/{id}/unpublish', {
    params: { path: { id } },
    body: { reason },
  })
  if (!response.ok) throw new ApiError(response.status, error)
}

export async function fetchRecents(): Promise<RecentSearchDto[]> {
  const { data, error, response } = await api.GET('/api/recents')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Record a just-executed search; returns the caller's updated recents (newest-first, capped). */
export async function postRecent(search: string, label: string): Promise<RecentSearchDto[]> {
  const { data, error, response } = await api.POST('/api/recents', { body: { search, label } })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * Case Inspector Phase 1: the drill behind the Stage-0 out-of-scope count — the enumerated
 * CMMN dead-letter jobs on one engine. Capability-gated 6.8+ server-side (a pre-6.8 engine
 * answers a ProblemDetail); the UI only opens this on engines whose count is a number.
 */
export async function fetchOutOfScopeDeadLetters(engineId: string): Promise<OutOfScopeDeadLetters> {
  const { data, error, response } = await api.GET(
    '/api/triage/engines/{engineId}/out-of-scope-deadletters',
    { params: { path: { engineId } } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * Case Inspector Phase 1: the scope-typed lane facet — CMMN case counts
 * (ACTIVE/FAILED/COMPLETED/TERMINATED) plus the FAILED-lane dead-letter detail on one engine.
 * Same 6.8+ server-side gate as {@link fetchOutOfScopeDeadLetters}; the UI only opens it on
 * engines whose out-of-scope count is a number.
 */
export async function fetchCmmnScope(engineId: string): Promise<CmmnScopeFacet> {
  const { data, error, response } = await api.GET('/api/triage/engines/{engineId}/cmmn-scope', {
    params: { path: { engineId } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/* ---- Case Inspector Phase 2: polymorphic Stage-2 CMMN case detail (read-only, 6.8+) ---- */

export interface CasePath {
  engineId: string
  caseInstanceId: string
}

export async function fetchCaseVitals(path: CasePath): Promise<CaseDetail> {
  const { data, error, response } = await api.GET('/api/cases/{engineId}/{caseInstanceId}', {
    params: { path },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchCaseDiagram(path: CasePath): Promise<CaseDiagram> {
  const { data, error, response } = await api.GET(
    '/api/cases/{engineId}/{caseInstanceId}/diagram',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchCasePlanItems(path: CasePath): Promise<CasePlanItems> {
  const { data, error, response } = await api.GET(
    '/api/cases/{engineId}/{caseInstanceId}/plan-items',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceAudit(
  engineId: string,
  instanceId: string,
): Promise<AuditEntryDto[]> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}/audit', {
    params: { path: { engineId, instanceId } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceNotes(engineId: string, instanceId: string): Promise<NoteDto[]> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}/notes', {
    params: { path: { engineId, instanceId } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/* ---------- Stage 2 detail reads (M3) — one fetcher per endpoint ---------- */

type InstancePath = { engineId: string; instanceId: string }

export async function fetchInstanceVitals(path: InstancePath): Promise<InstanceDetail> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}', {
    params: { path },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * "Explain this status" (R-L3-01, SPEC §3): the falsifiable derivation behind the status chip —
 * plan choice, each engine call's URL/body/status/duration/asOf, and per-flag provenance,
 * RE-DERIVED on demand (labeled as such; the original bytes are never retained).
 */
export async function fetchInstanceStatusEvidence(path: InstancePath): Promise<StatusEvidence> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/explain-status',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceDiagram(path: InstancePath): Promise<InstanceDiagram> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/diagram',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceVariables(path: InstancePath): Promise<InstanceVariables> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/variables',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * The escape hatch behind a >256 KiB truncated ledger row — explicit fetch only. Scoped:
 * pass {@code executionId} to read an execution-local ("step-local") variable (the base the
 * step-local editor stages/CASes against); omit it for the process (case) scope.
 */
export async function fetchInstanceVariable(
  path: InstancePath,
  name: string,
  executionId?: string,
): Promise<VariableDto> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/variables/{name}',
    {
      params: {
        path: { ...path, name },
        ...(executionId !== undefined ? { query: { executionId } } : {}),
      },
    },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceJobs(path: InstancePath): Promise<InstanceJobs> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}/jobs', {
    params: { path },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/**
 * External-worker jobs (v1.x #7) — the fifth queue, capability-gated. Only ever fetched on a
 * Flowable ≥ 6.8 engine (the caller enables the query off EngineDto.capabilities); the BFF is
 * the real gate and refuses on an older engine.
 */
export async function fetchInstanceExternalWorkerJobs(
  path: InstancePath,
): Promise<ExternalWorkerJobDto[]> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/jobs/external-worker',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Plain-text stacktrace, fetched on expand (SPEC §4) — never eagerly per row. */
export async function fetchJobStacktrace(
  path: InstancePath,
  jobId: string,
  lane: string,
): Promise<string> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/jobs/{jobId}/stacktrace',
    { params: { path: { ...path, jobId }, query: { lane } }, parseAs: 'text' },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceTasks(path: InstancePath): Promise<InstanceTasks> {
  const { data, error, response } = await api.GET('/api/instances/{engineId}/{instanceId}/tasks', {
    params: { path },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceHierarchy(path: InstancePath): Promise<InstanceHierarchy> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/hierarchy',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function fetchInstanceTimeline(path: InstancePath): Promise<InstanceTimeline> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/timeline',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/* ---------- Sibling diff (v1.x #5, SPEC §5.2) — read-only historic comparison ---------- */

/** The smart default: the most recently completed instance of the same definition version. */
export async function fetchNearestSibling(path: InstancePath): Promise<NearestSiblingResponse> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/nearest-sibling',
    { params: { path } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** The three-way diff of this instance (subject) against a chosen sibling. */
export async function fetchSiblingDiff(
  path: InstancePath,
  siblingId: string,
): Promise<SiblingDiffResponse> {
  const { data, error, response } = await api.GET(
    '/api/instances/{engineId}/{instanceId}/diff/{siblingId}',
    { params: { path: { ...path, siblingId } } },
  )
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** The omnibox resolver (R-SEM-04): one pasted string across all reachable engines. */
export async function resolveQuery(q: string): Promise<ResolveResponse> {
  const { data, error, response } = await api.GET('/api/resolve', { params: { query: { q } } })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export async function createInstanceNote(
  engineId: string,
  instanceId: string,
  body: string,
): Promise<NoteDto> {
  const { data, error, response } = await api.POST('/api/instances/{engineId}/{instanceId}/notes', {
    params: { path: { engineId, instanceId } },
    body: { body },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

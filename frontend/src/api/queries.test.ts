import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './client'
import {
  createInstanceNote,
  deleteSavedView,
  fetchCaseDiagram,
  fetchCasePlanItems,
  fetchCaseVitals,
  fetchCmmnScope,
  fetchEngines,
  fetchIncident,
  fetchIncidents,
  fetchInstanceAudit,
  fetchInstanceDiagram,
  fetchInstanceExternalWorkerJobs,
  fetchInstanceHierarchy,
  fetchInstanceJobs,
  fetchInstanceNotes,
  fetchInstanceStatusEvidence,
  fetchInstanceTasks,
  fetchInstanceTimeline,
  fetchInstanceVariable,
  fetchInstanceVariables,
  fetchInstanceVitals,
  fetchJobStacktrace,
  fetchLeakViews,
  fetchNearestSibling,
  fetchOutOfScopeDeadLetters,
  fetchRecents,
  fetchSavedViews,
  fetchSiblingDiff,
  fetchSystemInfo,
  fetchTeamViews,
  fetchTriage,
  fetchTriageTrends,
  postRecent,
  publishTeamView,
  putSavedView,
  resolveQuery,
  runPersonTaskSearch,
  runSearch,
  unpublishTeamView,
} from './queries'

vi.mock('./client', () => {
  class ApiError extends Error {
    readonly status: number
    readonly body: unknown
    constructor(status: number, body: unknown) {
      super(`HTTP ${String(status)}`)
      this.name = 'ApiError'
      this.status = status
      this.body = body
    }
  }
  return {
    ApiError,
    api: {
      GET: vi.fn(),
      POST: vi.fn(),
      PUT: vi.fn(),
      DELETE: vi.fn(),
    },
  }
})

const path = { engineId: 'eng', instanceId: 'pi-1' }
const casePath = { engineId: 'eng', caseInstanceId: 'case-1' }

function calls(): Array<() => Promise<unknown>> {
  return [
    () => fetchSystemInfo('eng'),
    () => fetchEngines(),
    () => runSearch({}),
    () => runPersonTaskSearch('ada'),
    () => runPersonTaskSearch('ada', ['eng']),
    () => fetchTriage(false),
    () => fetchTriage(true),
    () => fetchTriageTrends(24),
    () => fetchLeakViews(),
    () => fetchIncidents(),
    () => fetchIncidents('OPEN', 12),
    () => fetchIncident(7),
    () => fetchIncident(7, 6),
    () => fetchSavedViews(),
    () => putSavedView('mine', 'status=failed'),
    () => deleteSavedView(3),
    () => fetchTeamViews(),
    () => publishTeamView({ name: 'canon', search: 'q' }),
    () => unpublishTeamView(4, 'retired this view'),
    () => fetchRecents(),
    () => postRecent('q', 'label'),
    () => fetchOutOfScopeDeadLetters('eng'),
    () => fetchCmmnScope('eng'),
    () => fetchCaseVitals(casePath),
    () => fetchCaseDiagram(casePath),
    () => fetchCasePlanItems(casePath),
    () => fetchInstanceAudit('eng', 'pi-1'),
    () => fetchInstanceNotes('eng', 'pi-1'),
    () => fetchInstanceVitals(path),
    () => fetchInstanceStatusEvidence(path),
    () => fetchInstanceDiagram(path),
    () => fetchInstanceVariables(path),
    () => fetchInstanceVariable(path, 'amount'),
    () => fetchInstanceVariable(path, 'amount', 'exec-9'),
    () => fetchInstanceJobs(path),
    () => fetchInstanceExternalWorkerJobs(path),
    () => fetchJobStacktrace(path, 'job-1', 'deadletter'),
    () => fetchInstanceTasks(path),
    () => fetchInstanceHierarchy(path),
    () => fetchInstanceTimeline(path),
    () => fetchNearestSibling(path),
    () => fetchSiblingDiff(path, 'sib-1'),
    () => resolveQuery('ORDER-1'),
    () => createInstanceNote('eng', 'pi-1', 'noted'),
  ]
}

beforeEach(() => {
  vi.mocked(api.GET).mockReset()
  vi.mocked(api.POST).mockReset()
  vi.mocked(api.PUT).mockReset()
  vi.mocked(api.DELETE).mockReset()
})

describe('queries', () => {
  it('returns the body and sends the optional query knobs', async () => {
    const ok = { data: { ok: true }, error: undefined, response: { ok: true, status: 200 } }
    vi.mocked(api.GET).mockResolvedValue(ok)
    vi.mocked(api.POST).mockResolvedValue(ok)
    vi.mocked(api.PUT).mockResolvedValue(ok)
    vi.mocked(api.DELETE).mockResolvedValue({
      error: undefined,
      response: { ok: true, status: 204 },
    })

    for (const call of calls()) {
      await call()
    }

    expect(api.GET).toHaveBeenCalledWith('/api/triage', { params: { query: {} } })
    expect(api.GET).toHaveBeenCalledWith('/api/triage', {
      params: { query: { refresh: true } },
    })
    expect(api.GET).toHaveBeenCalledWith(
      '/api/instances/{engineId}/{instanceId}/variables/{name}',
      expect.objectContaining({
        params: expect.objectContaining({ query: { executionId: 'exec-9' } }) as unknown,
      }),
    )
    expect(api.DELETE).toHaveBeenCalledWith('/api/views/{id}', { params: { path: { id: 3 } } })
  })

  it('maps a missing body or a failed response to ApiError', async () => {
    const missing = {
      data: undefined,
      error: { detail: 'down' },
      response: { ok: false, status: 503 },
    }
    vi.mocked(api.GET).mockResolvedValue(missing)
    vi.mocked(api.POST).mockResolvedValue(missing)
    vi.mocked(api.PUT).mockResolvedValue(missing)
    vi.mocked(api.DELETE).mockResolvedValue(missing)

    for (const call of calls()) {
      await expect(call()).rejects.toBeInstanceOf(ApiError)
    }
  })
})

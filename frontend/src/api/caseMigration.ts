// Case migration at scale (#500). The cohort mirrors the process one: count → analysis (the
// single-case pre-check per case) → ONE bulk job over the analysis id. The three definition
// doors (by-definition batch, historic by definition, one historic case) exist on Flowable 7.x
// only: the on-ramp carries the gate reason for the greyed controls and the BFF refuses the
// call before any engine contact. Never optimistic — every mutation re-fetches on settle.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { parseActionProblem } from '../actions/problem'
import { ActionError } from './actions'
import type { ActionResult } from './actions'
import { BULK_JOBS_KEY } from './bulk'
import type { BulkJobDto } from './bulk'
import { ApiError, api } from './client'
import type { components } from './schema'

export type CaseDefinitionVersionsResponse = components['schemas']['CaseDefinitionVersionsResponse']
export type CaseDefinitionVersion = components['schemas']['CaseDefinitionVersion']
export type CaseMigrationBatchView = components['schemas']['CaseMigrationBatchView']
export type CaseCohortRequest = components['schemas']['CaseCohortRequest']
export type CaseCohortPreviewResponse = components['schemas']['CaseCohortPreviewResponse']
export type CaseCohortAnalysisView = components['schemas']['CaseCohortAnalysisView']
export type CaseDefinitionMigrationRequest = components['schemas']['CaseDefinitionMigrationRequest']
type BulkMigrateRequest = components['schemas']['BulkMigrateRequest']

/** Which 7.x-only definition door a modal drives. */
export type CaseDefinitionDoor = 'batch' | 'historic'

const versionsKey = (engineId: string, key: string | undefined) =>
  ['case-definition-versions', engineId, key] as const

/** The on-ramp: per-version RUNNING and ended case counts. Fires only when a key is known. */
export function useCaseDefinitionVersions(engineId: string, key: string | undefined) {
  return useQuery<CaseDefinitionVersionsResponse, ApiError>({
    queryKey: versionsKey(engineId, key),
    enabled: key !== undefined && key !== '',
    staleTime: 15_000,
    queryFn: async () => {
      const { data, error, response } = await api.GET(
        '/api/case-definitions/{engineId}/{key}/versions',
        { params: { path: { engineId, key: key ?? '' } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
}

/** The engine's case-migration batches from one version. Polls while one is still running. */
export function useCaseMigrationBatches(engineId: string, caseDefinitionId: string | undefined) {
  return useQuery<CaseMigrationBatchView[], ApiError>({
    queryKey: ['case-migration-batches', engineId, caseDefinitionId],
    enabled: caseDefinitionId !== undefined,
    queryFn: async () => {
      const { data, error, response } = await api.GET(
        '/api/case-definitions/{engineId}/{caseDefinitionId}/migration-batches',
        { params: { path: { engineId, caseDefinitionId: caseDefinitionId ?? '' } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
    refetchInterval: (query) =>
      (query.state.data ?? []).some((batch) => batch.completeTime == null) ? 3000 : false,
  })
}

export async function previewCaseCohort(
  engineId: string,
  body: CaseCohortRequest,
): Promise<CaseCohortPreviewResponse> {
  const { data, error, response } = await api.POST('/api/case-migration/cohorts/preview', {
    params: { query: { engineId } },
    body,
  })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function startCaseAnalysis(
  engineId: string,
  body: CaseCohortRequest,
): Promise<CaseCohortAnalysisView> {
  const { data, error, response } = await api.POST('/api/case-migration/analyses', {
    params: { query: { engineId } },
    body,
  })
  if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
  return data
}

export async function fetchCaseAnalysis(id: string): Promise<CaseCohortAnalysisView> {
  const { data, error, response } = await api.GET('/api/case-migration/analyses/{id}', {
    params: { path: { id } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** The BFF takes each completed case analysis's executable set; no case id crosses the wire. */
export function useSubmitBulkMigrateCases() {
  const queryClient = useQueryClient()
  return useMutation<BulkJobDto, ActionError, BulkMigrateRequest>({
    retry: false,
    mutationFn: async (body) => {
      const { data, error, response } = await api.POST('/api/bulk/migrate-cases', { body })
      if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
      return data
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: BULK_JOBS_KEY })
    },
  })
}

/** One of the two by-definition doors (Flowable 7.x only). */
export function useCaseDefinitionMigrate(
  engineId: string,
  caseDefinitionId: string,
  door: CaseDefinitionDoor,
) {
  const queryClient = useQueryClient()
  return useMutation<ActionResult, ActionError, CaseDefinitionMigrationRequest>({
    retry: false,
    mutationFn: async (body) => {
      const params = { path: { engineId, caseDefinitionId } }
      const { data, error, response } =
        door === 'batch'
          ? await api.POST('/api/case-definitions/{engineId}/{caseDefinitionId}/migrate-batch', {
              params,
              body,
            })
          : await api.POST('/api/case-definitions/{engineId}/{caseDefinitionId}/migrate-historic', {
              params,
              body,
            })
      if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
      return data
    },
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['case-definition-versions', engineId] }),
        queryClient.invalidateQueries({ queryKey: ['case-migration-batches', engineId] }),
      ])
    },
  })
}

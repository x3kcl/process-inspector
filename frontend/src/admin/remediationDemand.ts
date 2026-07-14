// Issue #106 S0 (R-GOV-08 "honest gating"): the one-time evidence check for the v2
// remediation-playbooks build trigger. Read-only — nothing here is persisted or scheduled.
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type { components } from '../api/schema'

export type RemediationDemandAnalysis = components['schemas']['RemediationDemandAnalysis']
export type SequenceFinding = components['schemas']['SequenceFinding']

const QUERY_KEY = ['remediationDemand'] as const

async function fetchAnalysis(): Promise<RemediationDemandAnalysis> {
  const { data, error, response } = await api.GET('/api/admin/remediation-demand')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export function useRemediationDemand() {
  return useQuery({ queryKey: QUERY_KEY, queryFn: fetchAnalysis, retry: false })
}

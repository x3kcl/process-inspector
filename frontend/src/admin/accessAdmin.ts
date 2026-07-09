// v2 IdP-Security S6 admin surface (docs/IDP-SECURITY.md §6/§10/§12). The apex group→scope
// mapping — ACCESS_ADMIN only, greyed-never-hidden. Every call goes through the generated
// openapi-fetch client; types come from schema.d.ts (never hand-written). Widening writes return
// a `proposed` outcome (four-eyes) with the eligible-approver set; a second ACCESS_ADMIN approves.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type { components } from '../api/schema'

export type AccessMappingDto = components['schemas']['AccessMappingDto']
export type LadderView = components['schemas']['LadderView']
export type FleetView = components['schemas']['FleetView']
export type ProposalView = components['schemas']['ProposalView']
export type Outcome = components['schemas']['Outcome']
export type GrantRequest = components['schemas']['GrantRequest']

const MAPPING_KEY = ['accessMapping'] as const
const PROPOSALS_KEY = ['accessProposals'] as const

async function fetchMapping(): Promise<AccessMappingDto> {
  const { data, error, response } = await api.GET('/api/admin/access')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function fetchProposals(): Promise<ProposalView[]> {
  const { data, error, response } = await api.GET('/api/admin/access/proposals')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function addGrant(body: GrantRequest): Promise<Outcome> {
  const { data, error, response } = await api.POST('/api/admin/access/grants', { body })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function removeGrant(body: GrantRequest): Promise<Outcome> {
  const { data, error, response } = await api.DELETE('/api/admin/access/grants', { body })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

async function approveProposal(id: number): Promise<Outcome> {
  const { data, error, response } = await api.POST('/api/admin/access/proposals/{id}/approve', {
    params: { path: { id } },
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export function useAccessMapping() {
  return useQuery({ queryKey: MAPPING_KEY, queryFn: fetchMapping, retry: false })
}

export function useProposals() {
  return useQuery({ queryKey: PROPOSALS_KEY, queryFn: fetchProposals, retry: false })
}

export function useAccessMutations() {
  const queryClient = useQueryClient()
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: MAPPING_KEY })
    void queryClient.invalidateQueries({ queryKey: PROPOSALS_KEY })
  }
  return {
    add: useMutation({ mutationFn: addGrant, onSuccess: invalidate }),
    remove: useMutation({ mutationFn: removeGrant, onSuccess: invalidate }),
    approve: useMutation({ mutationFn: approveProposal, onSuccess: invalidate }),
  }
}

/**
 * Download the access-review export (the R-GOV-02 "who can do what" artifact) as a file. The
 * endpoint answers `?format=csv|md` with a Content-Disposition attachment; we fetch it as a blob
 * and save it (the session cookie rides the fetch). JSON display uses the typed mapping instead.
 */
export async function downloadAccessReview(format: 'csv' | 'md'): Promise<void> {
  const resp = await fetch(`/api/access-review?format=${format}`, { credentials: 'same-origin' })
  if (!resp.ok) throw new ApiError(resp.status, undefined)
  const blob = await resp.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `access-review.${format}`
  document.body.appendChild(a)
  a.click()
  a.remove()
  // Defer the revoke: revoking synchronously right after click() can cancel the download in some
  // browsers before it starts (Copilot S6 review). A next-tick timeout is the safe idiom.
  setTimeout(() => {
    URL.revokeObjectURL(url)
  }, 0)
}

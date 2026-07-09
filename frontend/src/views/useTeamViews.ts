// Team (shared) views (v2, SHARED-VIEWS.md, R-SEM-24/R-SAFE-16): the curated canon an
// operator/admin publishes for the whole team (or a tenant/engine scope). Unlike the per-user
// saved views, these are governed server-side — publish/unpublish gate on covers()/author-or-ADMIN
// and are audited. The frontend only lists what the caller may SEE (an overlaps() DECLUTTER filter,
// not a security boundary) and offers the deliberate publish/unpublish acts; every rule is the BFF's.
import { useCallback } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { TeamViewDto } from '../api/model'
import { fetchTeamViews, publishTeamView, unpublishTeamView } from '../api/queries'
import { normalizeSearch } from './model'

const TEAM_VIEWS_KEY = ['teamViews'] as const

export interface PublishInput {
  name: string
  search: string
  description?: string
  runbookUrl?: string
}

export interface TeamViewsApi {
  views: TeamViewDto[]
  publish: (input: PublishInput) => Promise<TeamViewDto>
  unpublish: (id: number, reason?: string) => Promise<void>
}

export function useTeamViews(): TeamViewsApi {
  const queryClient = useQueryClient()
  const { data } = useQuery({ queryKey: TEAM_VIEWS_KEY, queryFn: fetchTeamViews })
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: TEAM_VIEWS_KEY })

  const publishMutation = useMutation({
    mutationFn: (input: PublishInput) =>
      publishTeamView({
        name: input.name.trim(),
        search: normalizeSearch(input.search),
        description: input.description,
        runbookUrl: input.runbookUrl,
      }),
    onSuccess: invalidate,
  })
  const unpublishMutation = useMutation({
    mutationFn: (vars: { id: number; reason?: string }) => unpublishTeamView(vars.id, vars.reason),
    onSuccess: invalidate,
  })

  // mutateAsync so the caller can await + surface the server's 403/400/409 inline (governed rails).
  const publish = useCallback(
    (input: PublishInput) => publishMutation.mutateAsync(input),
    [publishMutation],
  )
  const unpublish = useCallback(
    (id: number, reason?: string) => unpublishMutation.mutateAsync({ id, reason }),
    [unpublishMutation],
  )

  return { views: data ?? [], publish, unpublish }
}

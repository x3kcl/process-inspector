// GET /api/meta — the small SPA config channel (OPERATIONS §5). Currently the boot-validated
// ticket-url-template (R-AUD-07) the audit surfaces linkify a ticketId with. Config, not identity:
// long stale time, and a failure is non-fatal (the surfaces fall back to plain-text ticketIds).
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from './client'
import type { components } from './schema'

export type MetaDto = components['schemas']['MetaDto']

export async function fetchMeta(): Promise<MetaDto> {
  const { data, error, response } = await api.GET('/api/meta')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export function useMeta() {
  return useQuery({
    queryKey: ['meta'],
    queryFn: fetchMeta,
    staleTime: Infinity,
    retry: false,
  })
}

/** The configured ticket link template, or undefined when none is set / meta hasn't loaded. */
export function useTicketUrlTemplate(): string | undefined {
  const meta = useMeta()
  return meta.data?.ticketUrlTemplate ?? undefined
}

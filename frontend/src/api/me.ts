// Real role-greying (SPEC §6 greyed-never-hidden): /api/me resolves the ladder role per
// engine through the SAME RbacAuthorizer path the BFF's guards use, so what the UI greys
// and what the BFF refuses cannot drift. Presentation only — the BFF check stays the gate.
import { useQuery } from '@tanstack/react-query'
import type { RoleHint } from '../actions/catalog'
import { api, ApiError } from './client'
import type { components } from './schema'

export type MeDto = components['schemas']['MeDto']

export async function fetchMe(): Promise<MeDto> {
  const { data, error, response } = await api.GET('/api/me')
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

const ROLES: readonly RoleHint[] = ['VIEWER', 'RESPONDER', 'OPERATOR', 'ADMIN']

function asRole(value: string | undefined): RoleHint | null {
  return value !== undefined && (ROLES as readonly string[]).includes(value)
    ? (value as RoleHint)
    : null
}

export function useMe() {
  return useQuery({
    queryKey: ['me'],
    queryFn: fetchMe,
    // Identity changes only on re-login; SignIn invalidates ['me'] explicitly.
    staleTime: Infinity,
    retry: false,
  })
}

/**
 * The per-engine role for gating. null = unknown (me not loaded / engine unmapped):
 * the UI stays optimistic and the BFF 403 answers — never grey on a guess.
 */
export function roleOn(me: MeDto | undefined, engineId: string): RoleHint | null {
  if (me === undefined) return null
  return asRole(me.engineRoles?.[engineId]) ?? asRole(me.role ?? undefined)
}

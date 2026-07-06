// Best-effort role discovery for greyed-never-hidden affordances (SPEC §6). There is no
// /api/me endpoint; the dev sign-in chain uses the ladder usernames verbatim
// (SecurityConfig dev profile), so the username in the stored Basic token IS the role.
// Anything else (OIDC later, custom users) yields null = unknown, and the UI stays
// optimistic — the BFF's 403 is always the real gate, this is presentation only.
import { getBasicAuth } from '../api/auth'
import type { RoleHint } from '../actions/catalog'

const LADDER: Record<string, RoleHint> = {
  viewer: 'VIEWER',
  responder: 'RESPONDER',
  operator: 'OPERATOR',
  admin: 'ADMIN',
}

export function roleFromUsername(username: string): RoleHint | null {
  return LADDER[username.toLowerCase()] ?? null
}

export function currentRoleHint(): RoleHint | null {
  const token = getBasicAuth()
  if (token === null) return null
  try {
    const decoded = atob(token)
    const colon = decoded.indexOf(':')
    if (colon <= 0) return null
    return roleFromUsername(decoded.slice(0, colon))
  } catch {
    return null
  }
}

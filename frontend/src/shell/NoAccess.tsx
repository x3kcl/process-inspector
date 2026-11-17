import type { MeDto } from '../api/me'

/**
 * #758 (R-SAFE-17): signed in, but no role on any engine and no fleet grant — what an OIDC user
 * whose groups map to nothing gets. Decided from GET /api/me alone; the BFF sends `role: null`
 * (an explicit null, never just absent) for such a user, so a partial mock never trips it.
 */
export function hasNoAccess(me: MeDto | undefined): boolean {
  if (me === undefined) return false
  const role: unknown = me.role
  return (
    role === null &&
    Object.keys(me.engineRoles ?? {}).length === 0 &&
    me.registryAdmin !== true &&
    me.accessAdmin !== true &&
    me.breakGlass !== true
  )
}

/** The page shown instead of every route while the user has no access (no gated call is made). */
export function NoAccess() {
  return (
    <div className="no-access">
      <h2>No access granted yet</h2>
      <p>
        You are signed in, but no access has been granted yet — ask an Inspector admin to map your
        group to a role on at least one engine.
      </p>
    </div>
  )
}

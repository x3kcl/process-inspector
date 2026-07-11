import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { clearBasicAuth } from '../api/auth'
import { postLogout } from '../api/client'
import { useMe } from '../api/me'
import { setSignedOut } from '../api/session'

/**
 * The dev identity affordance (usability W3): "who am I" + a Sign out control in the header.
 * The baseline run hit this as a Sev2 gap — no signed-in-as, no way to drop privilege, and
 * /logout 404'd — so role-switching meant guessing the BFF form. Dev-appropriate only: it
 * reads GET /api/me and drives the same dev basic/cookie chain. It never touches the OIDC or
 * break-glass chains (those own their own redirect/seal flows).
 *
 * Sign out: clear the client Basic creds, POST the CSRF-safe server logout (invalidates the
 * session the dev chain persists — clearing creds alone would NOT sign out, the cookie still
 * authenticates), drop the cache, then flip the explicit sign-out flag so Shell renders SignIn
 * even though no query has answered 401.
 */
export function IdentityStrip() {
  const me = useMe()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)

  // Nothing to show until identity resolves (and it is hidden entirely once signed out, since
  // Shell overlays SignIn and the ['me'] cache was cleared).
  if (me.data === undefined) return null
  const { username, role } = me.data

  const signOut = () => {
    setBusy(true)
    clearBasicAuth()
    void postLogout()
      .catch(() => {
        // Best-effort: the dev session cookie is same-origin only; the creds are already gone.
      })
      .finally(() => {
        // Settle local state BEFORE flipping the store: setSignedOut(true) makes Shell render
        // SignIn and unmount this strip, so the store flip goes last to avoid a state update
        // on the unmounting component.
        setBusy(false)
        queryClient.clear()
        setSignedOut(true)
      })
  }

  return (
    <span className="identity-strip" role="group" aria-label="Signed-in identity">
      <span className="identity-who">
        <span className="identity-label">Signed in as</span>{' '}
        <strong>{username ?? 'unknown'}</strong>
        {role !== undefined && role !== '' && <span className="identity-role"> ({role})</span>}
      </span>
      <button type="button" className="topbar-link identity-signout" onClick={signOut} disabled={busy}>
        {busy ? 'Signing out…' : 'Sign out'}
      </button>
    </span>
  )
}

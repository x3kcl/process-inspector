// The re-auth interstitial notice (IDP-SECURITY.md §5): rendered inside a dangerous-action
// modal either PRE-EMPTIVELY (the /api/me `reauth` hint says the session is already stale at
// modal open — before the operator types anything) or REACTIVELY (the submit answered the 401
// `reauth-required` challenge). The button is a real top-level navigation to the oidc re-auth
// entry; the current route is checkpointed and restored after the round-trip (auth/reauth.ts).
import { useMe } from '../api/me'
import { checkpointAndReauth, reauthStale, reauthWindowMinutes } from '../auth/reauth'

/**
 * Is the signed-in session already too stale for a dangerous verb? Read at modal open so the
 * interstitial shows BEFORE the reason/token are typed (never after — ⚠️ support-lead). Always
 * false on the dev/basic and break-glass chains (the hint says so server-side).
 */
export function useReauthStale(): boolean {
  const me = useMe()
  return reauthStale(me.data?.reauth, Date.now())
}

export function ReauthNotice() {
  const me = useMe()
  const minutes = reauthWindowMinutes(me.data?.reauth)
  return (
    <div className="error-banner reauth-notice" role="alert">
      <p>
        This action needs a sign-in newer than {String(minutes)} minutes. Re-authenticate to
        continue — you will land back on this page, and nothing has happened yet.
      </p>
      <button
        type="button"
        onClick={() => {
          checkpointAndReauth()
        }}
      >
        Re-authenticate now
      </button>
    </div>
  )
}

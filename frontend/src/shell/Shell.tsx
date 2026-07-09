import { useSyncExternalStore } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, Outlet } from 'react-router'
import { ApiError } from '../api/client'
import { useMe } from '../api/me'
import { HeaderStrip } from '../components/HeaderStrip'
import { SignIn } from '../components/SignIn'
import { ToastProvider } from '../components/toast'
import { LiveProvider } from '../live/live'
import { OpsDrawer } from '../ops/OpsDrawer'
import { OpsDrawerProvider } from '../ops/drawerState'
import { useLegacyViewMigration } from '../views/legacyMigration'
import { Omnibox } from './Omnibox'

/**
 * The stage-independent frame (SPEC §4): topbar with the app title, the omnibox (pinned on
 * every stage) and the M1 engine health strip; the active stage renders below. Sign-in
 * appears the moment ANY query in the cache answers 401 — pages never manage auth.
 */
export function Shell() {
  const authRequired = useAnyAuthError()
  // v2/M4: one-time migration of any v1 localStorage views/recents into the server store, once
  // the user is authenticated (SPEC §8).
  useLegacyViewMigration(!authRequired)
  return (
    <ToastProvider>
      {/* App-scoped live channel (live-ui-sse): ONE EventSource for every surface — a
          drawer-scoped stream would go deaf while closed. Gated on auth: the stream
          rides the session cookie the first authenticated call establishes. */}
      <LiveProvider enabled={!authRequired}>
        <OpsDrawerProvider>
          <div className="app">
            <BreakGlassBanner />
            <header className="topbar">
              <h1>
                <Link to="/" className="home-link">
                  Flowable Process Inspector
                </Link>
              </h1>
              <Link to="/audit" className="topbar-link">
                Ops log
              </Link>
              <RegistryAdminLink />
              <AccessAdminLink />
              <Omnibox />
              <HeaderStrip />
            </header>
            {authRequired && <SignIn />}
            <Outlet />
            <OpsDrawer />
          </div>
        </OpsDrawerProvider>
      </LiveProvider>
    </ToastProvider>
  )
}

/**
 * The registry-admin nav entry — greyed-never-hidden (R-UXQ): everyone sees "Engines", but a
 * non-REGISTRY_ADMIN gets a greyed, unlinked label with the reason, mirroring the BFF gate.
 */
function RegistryAdminLink() {
  const me = useMe()
  if (me.data?.registryAdmin === true) {
    return (
      <Link to="/admin/engines" className="topbar-link">
        Engines
      </Link>
    )
  }
  return (
    <span
      className="topbar-link disabled"
      aria-disabled="true"
      aria-label="Engines — requires the REGISTRY_ADMIN grant"
      title="Requires REGISTRY_ADMIN"
    >
      Engines
    </span>
  )
}

/**
 * The apex mapping-admin nav entry — greyed-never-hidden (IDP-SECURITY.md §12): everyone sees
 * "Access", but a non-ACCESS_ADMIN gets a greyed, unlinked label mirroring the BFF gate.
 */
function AccessAdminLink() {
  const me = useMe()
  if (me.data?.accessAdmin === true) {
    return (
      <Link to="/admin/access" className="topbar-link">
        Access
      </Link>
    )
  }
  return (
    <span
      className="topbar-link disabled"
      aria-disabled="true"
      aria-label="Access — requires the ACCESS_ADMIN grant"
      title="Requires ACCESS_ADMIN"
    >
      Access
    </span>
  )
}

/**
 * The permanent, loud break-glass banner (IDP-SECURITY.md §7, R-SAFE-11): while the session is a
 * sealed break-glass one, a red page banner is always visible — the operator can never forget they
 * are in break-glass, and every action is reason-gated + alert-fired server-side.
 */
function BreakGlassBanner() {
  const me = useMe()
  if (me.data?.breakGlass !== true) return null
  return (
    <div className="banner banner-breakglass" role="alert">
      ⚠ BREAK-GLASS SESSION — sealed emergency access (ADMIN-global, never fleet). Every action is
      logged and alerted; use only during an identity-provider outage, then rotate the credential.
    </div>
  )
}

/** True while any cached query's latest error is a 401 (dev sign-in chain trigger). */
function useAnyAuthError(): boolean {
  const queryClient = useQueryClient()
  const cache = queryClient.getQueryCache()
  return useSyncExternalStore(
    (onStoreChange) => cache.subscribe(onStoreChange),
    () =>
      cache
        .getAll()
        .some((query) => query.state.error instanceof ApiError && query.state.error.status === 401),
  )
}

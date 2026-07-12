import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { useMe } from '../api/me'
import { isSignedOut, subscribeSignedOut } from '../api/session'
import {
  checkpointAndReauth,
  consumeResume,
  isReauthBody,
  sessionExpiryState,
} from '../auth/reauth'
import { HeaderStrip } from '../components/HeaderStrip'
import { IdentityStrip } from '../components/IdentityStrip'
import { SignIn } from '../components/SignIn'
import { ZoneToggle } from '../components/ZoneToggle'
import { ToastProvider } from '../components/toast'
import { LiveProvider } from '../live/live'
import { OpsDrawer } from '../ops/OpsDrawer'
import { OpsDrawerProvider } from '../ops/drawerState'
import { useLegacyViewMigration } from '../views/legacyMigration'
import { Omnibox } from './Omnibox'
import { restoreRouteFocus } from './routeFocus'

/**
 * The stage-independent frame (SPEC §4): topbar with the app title, the omnibox (pinned on
 * every stage) and the M1 engine health strip; the active stage renders below. Sign-in
 * appears the moment ANY query in the cache answers 401 — pages never manage auth.
 */
export function Shell() {
  // Sign-in shows on EITHER a 401 (session expired / never signed in) OR an explicit dev
  // sign-out (usability W3) — the latter forces the form before any query errors. Both hooks
  // run unconditionally (rules-of-hooks); the `||` only combines their results.
  const authError = useAnyAuthError()
  const signedOut = useSignedOut()
  const authRequired = authError || signedOut
  // v2/M4: one-time migration of any v1 localStorage views/recents into the server store, once
  // the user is authenticated (SPEC §8).
  useLegacyViewMigration(!authRequired)
  // Re-auth round-trip landing (IDP-SECURITY.md §5): the dangerous-set challenge is a full-page
  // OIDC redirect, so the pre-redirect route is checkpointed to sessionStorage; on the fresh boot
  // after login we restore it (single-shot, TTL-bounded, same-origin-path-only).
  useResumeAfterReauth()
  // R-UXQ-02: after a route change, focus never stays on <body> — it lands on the new
  // route's main heading (or nearest survivor).
  useRouteFocus()
  return (
    <ToastProvider>
      {/* App-scoped live channel (live-ui-sse): ONE EventSource for every surface — a
          drawer-scoped stream would go deaf while closed. Gated on auth: the stream
          rides the session cookie the first authenticated call establishes. */}
      <LiveProvider enabled={!authRequired}>
        <OpsDrawerProvider>
          <div className="app">
            <BreakGlassBanner />
            <SessionExpiryBanner />
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
              {/* R-UXQ-03: the one-click display-zone control lives on every stage. */}
              <ZoneToggle />
              {/* Usability W3: who-am-I + Sign out (dev ladder). Hidden while signed out. */}
              {!authRequired && <IdentityStrip />}
              <HeaderStrip />
            </header>
            {authRequired && <SignIn />}
            {/* The app's ONE <main> landmark (axe landmark-one-main): every route's own
                top-level element is a plain <div> so this never nests. */}
            <main>
              <Outlet />
            </main>
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

/**
 * Warn-before-guillotine (IDP-SECURITY.md §5, R-SAFE-07): a passive countdown banner — NEVER a
 * takeover over a dirty form (R-UXQ-06) — once the absolute session cap is ≤30 min away. On an
 * OIDC session (reauth hint carries a freshUntil) the CTA re-authenticates in place, minting a
 * fresh session; a break-glass session gets the countdown WITHOUT a CTA — its 4 h cap exists
 * because the IdP is down, so there is nothing to bounce through (rotate or re-break-glass).
 * The clock ticks every 30 s; me is cached (staleTime Infinity) so this costs no refetching.
 */
function SessionExpiryBanner() {
  const me = useMe()
  const [nowMs, setNowMs] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => {
      setNowMs(Date.now())
    }, 30_000)
    return () => {
      clearInterval(timer)
    }
  }, [])
  const expiry = sessionExpiryState(me.data?.sessionExpiresAt, nowMs)
  if (!expiry.show) return null
  // A freshness-tracked OIDC session — the only chain a re-auth bounce helps — carries a string
  // freshUntil (or required=true when auth_time was absent). Dev basic AND break-glass answer
  // {required:false, freshUntil:null}; Jackson sends null where the generated type says
  // undefined (known wire gotcha), so the check is typeof, never !== undefined.
  const hint = me.data?.reauth
  const canReauth = typeof hint?.freshUntil === 'string' || hint?.required === true
  return (
    <div className="banner banner-warn session-expiry-banner" role="status">
      Session expires in {String(expiry.minutesLeft)} min — the absolute cap ends it regardless of
      activity.{' '}
      {canReauth ? (
        <button
          type="button"
          onClick={() => {
            checkpointAndReauth()
          }}
        >
          Re-authenticate now
        </button>
      ) : (
        'Finish or hand over in-flight work before it does.'
      )}
    </div>
  )
}

/**
 * True while any cached query's latest error is a 401 (dev sign-in chain trigger). A 401 carrying
 * the `reauth-required` challenge is EXCLUDED — that is a freshness interstitial on a still-valid
 * session (IDP-SECURITY.md §5), never a sign-out; today it only occurs on mutations (which live in
 * the mutation cache, not here), so this guard is defence-in-depth against a future gated query.
 */
function useAnyAuthError(): boolean {
  const queryClient = useQueryClient()
  const cache = queryClient.getQueryCache()
  return useSyncExternalStore(
    (onStoreChange) => cache.subscribe(onStoreChange),
    () =>
      cache
        .getAll()
        .some(
          (query) =>
            query.state.error instanceof ApiError &&
            query.state.error.status === 401 &&
            !isReauthBody(query.state.error.body),
        ),
  )
}

/** Subscribe to the explicit dev sign-out flag (usability W3), SSR-safe via a store snapshot. */
function useSignedOut(): boolean {
  return useSyncExternalStore(subscribeSignedOut, isSignedOut, isSignedOut)
}

/**
 * Route-change focus management (R-UXQ-02, usability W1#5): SPA navigation drops focus
 * onto <body>, forcing keyboard users to re-Tab from the page top. After each pathname
 * change, hand focus to the new route's main heading — unless a surviving element (the
 * link just activated, a tab button on a ?tab= switch) still holds it; restoreRouteFocus
 * never steals focus. Keyed on pathname only: same-route param changes keep their focus.
 * The initial load keeps the browser's default (address-bar → document) focus order.
 */
function useRouteFocus() {
  const { pathname } = useLocation()
  const isInitialLoad = useRef(true)
  useEffect(() => {
    if (isInitialLoad.current) {
      isInitialLoad.current = false
      return
    }
    // Next macrotask: lazy routes (CasePage, tab chunks) commit a Suspense fallback
    // first — give the route one tick to mount its landmark before targeting it.
    const timer = window.setTimeout(() => restoreRouteFocus(), 0)
    return () => {
      clearTimeout(timer)
    }
  }, [pathname])
}

/**
 * Restore the pre-re-auth route on the first boot after the OIDC round-trip. Only fires when the
 * app landed on the default post-login target '/' — a deep-linked landing wins over the checkpoint
 * (the checkpoint is popped either way; it is single-shot).
 */
function useResumeAfterReauth() {
  const navigate = useNavigate()
  useEffect(() => {
    const href = consumeResume()
    if (href !== null && window.location.pathname === '/') {
      void navigate(href, { replace: true })
    }
    // mount-only: the checkpoint exists only across the full-page redirect boundary
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}

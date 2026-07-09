import { useSyncExternalStore } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, Outlet } from 'react-router'
import { ApiError } from '../api/client'
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
            <header className="topbar">
              <h1>
                <Link to="/" className="home-link">
                  Flowable Process Inspector
                </Link>
              </h1>
              <Link to="/audit" className="topbar-link">
                Ops log
              </Link>
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

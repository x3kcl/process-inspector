import { useSyncExternalStore } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, Outlet } from 'react-router'
import { ApiError } from '../api/client'
import { HeaderStrip } from '../components/HeaderStrip'
import { SignIn } from '../components/SignIn'
import { ToastProvider } from '../components/toast'
import { Omnibox } from './Omnibox'

/**
 * The stage-independent frame (SPEC §4): topbar with the app title, the omnibox (pinned on
 * every stage) and the M1 engine health strip; the active stage renders below. Sign-in
 * appears the moment ANY query in the cache answers 401 — pages never manage auth.
 */
export function Shell() {
  const authRequired = useAnyAuthError()
  return (
    <ToastProvider>
      <div className="app">
        <header className="topbar">
          <h1>
            <Link to="/" className="home-link">
              Flowable Process Inspector
            </Link>
          </h1>
          <Omnibox />
          <HeaderStrip />
        </header>
        {authRequired && <SignIn />}
        <Outlet />
      </div>
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

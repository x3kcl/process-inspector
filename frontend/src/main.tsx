import { StrictMode, Suspense, lazy } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Navigate, RouterProvider, createBrowserRouter, useSearchParams } from 'react-router'
import { AdminEnginesPage } from './admin/AdminEnginesPage'
import { ApiError } from './api/client'
import { InspectPage } from './inspect/InspectPage'
import { AuditLogPage } from './ops/AuditLogPage'
import { SearchPage } from './search/SearchPage'
import { hasSearch } from './search/urlState'
import { Shell } from './shell/Shell'
import { DefinitionVersionsPage } from './inspect/DefinitionVersionsPage'
import { TriagePage } from './triage/TriagePage'
import './styles.css'

// The CMMN case detail pulls in cmmn-js (heavy) and is a rare path — keep it out of the
// initial bundle, mirroring the lazy Stage-2 tab chunks.
const CasePage = lazy(() => import('./case/CasePage').then((m) => ({ default: m.CasePage })))

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      // 4xx (bad filter, auth) will not heal on retry; engine-side 5xx gets one more try.
      retry: (failureCount, error) =>
        failureCount < 2 && !(error instanceof ApiError && error.status < 500),
    },
  },
})

/**
 * Pre-M3, "/" was Stage 1 and shared M2b links encoded the search on the root path.
 * Shared links are an incident primitive — they must keep replaying, so a root URL that
 * carries search params forwards to /search verbatim. A bare "/" is Stage 0 triage.
 */
function HomeRoute() {
  const [params] = useSearchParams()
  if (hasSearch(params)) return <Navigate to={`/search?${params.toString()}`} replace />
  return <TriagePage />
}

// SPEC §4: three stages, three routes — triage lands first, search is Stage 1,
// /inspect/{engineId}/{id} is the full-page, deep-linkable Stage 2.
const router = createBrowserRouter([
  {
    path: '/',
    element: <Shell />,
    children: [
      { index: true, element: <HomeRoute /> },
      { path: 'search', element: <SearchPage /> },
      { path: 'inspect/:engineId/:instanceId', element: <InspectPage /> },
      // Case Inspector Phase 2: the polymorphic CMMN sibling of /inspect (read-only, 6.8+).
      {
        path: 'case/:engineId/:caseInstanceId',
        element: (
          <Suspense fallback={<p className="muted">Loading case…</p>}>
            <CasePage />
          </Suspense>
        ),
      },
      { path: 'audit', element: <AuditLogPage /> },
      { path: 'admin/engines', element: <AdminEnginesPage /> },
      {
        path: 'definitions/:engineId/:key/versions',
        element: <DefinitionVersionsPage />,
      },
    ],
  },
])

const rootElement = document.getElementById('root')
if (rootElement === null) throw new Error('missing #root element')

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)

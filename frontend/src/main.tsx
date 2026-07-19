import { StrictMode, Suspense, lazy, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Navigate, RouterProvider, createBrowserRouter, useSearchParams } from 'react-router'
import { ApiError } from './api/client'
import { hasSearch } from './search/urlState'
import { Shell } from './shell/Shell'
import { TriagePage } from './triage/TriagePage'
// #104 slice 2b (R-UXQ-08): import for its module-load side effect only — applies the
// persisted/system theme to <html> before the first render, so there's no flash of the
// wrong explicit override.
import './lib/theme'
// #104 slice 3/6 (R-UXQ-09): same rationale as theme.ts above — applies the persisted grid
// density to <html> before the first render.
import './lib/density'
import './styles.css'

// U3 (#88): code-split every page that carries a heavy dependency out of the entry chunk. Only
// TriagePage (the Stage-0 landing) stays eager. /inspect pulls in bpmn-js and /search + /audit pull
// in ag-grid — the two biggest deps — so lazy-loading them (mirroring the already-lazy CasePage +
// Stage-2 tab chunks) collapses the ~1.5 MB entry to ~240 kB; each heavy vendor is further isolated
// into its own long-cacheable chunk via manualChunks (see vite.config.ts) and loaded only on its route.
const SearchPage = lazy(() =>
  import('./search/SearchPage').then((m) => ({ default: m.SearchPage })),
)
const InspectPage = lazy(() =>
  import('./inspect/InspectPage').then((m) => ({ default: m.InspectPage })),
)
const CasePage = lazy(() => import('./case/CasePage').then((m) => ({ default: m.CasePage })))
const AuditLogPage = lazy(() =>
  import('./ops/AuditLogPage').then((m) => ({ default: m.AuditLogPage })),
)
const AdminEnginesPage = lazy(() =>
  import('./admin/AdminEnginesPage').then((m) => ({ default: m.AdminEnginesPage })),
)
const AdminAccessPage = lazy(() =>
  import('./admin/AdminAccessPage').then((m) => ({ default: m.AdminAccessPage })),
)
const DefinitionVersionsPage = lazy(() =>
  import('./inspect/DefinitionVersionsPage').then((m) => ({ default: m.DefinitionVersionsPage })),
)
const PersonTaskSearchPage = lazy(() =>
  import('./tasks/PersonTaskSearchPage').then((m) => ({ default: m.PersonTaskSearchPage })),
)
const RemediationDemandPage = lazy(() =>
  import('./admin/RemediationDemandPage').then((m) => ({ default: m.RemediationDemandPage })),
)
// Incident Ledger (R-BAU-10, docs/INCIDENT-LEDGER.md §8) — persisted failure-class history,
// distinct from the Stage-0 landing above.
const IncidentsPage = lazy(() =>
  import('./incidents/IncidentsPage').then((m) => ({ default: m.IncidentsPage })),
)
const IncidentDetail = lazy(() =>
  import('./incidents/IncidentDetail').then((m) => ({ default: m.IncidentDetail })),
)

/** Wrap a lazily-loaded route element in a Suspense boundary with a consistent fallback. */
function lazyRoute(node: ReactNode): ReactNode {
  return <Suspense fallback={<p className="muted">Loading…</p>}>{node}</Suspense>
}

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
      { path: 'search', element: lazyRoute(<SearchPage />) },
      { path: 'tasks', element: lazyRoute(<PersonTaskSearchPage />) },
      { path: 'incidents', element: lazyRoute(<IncidentsPage />) },
      { path: 'incidents/:id', element: lazyRoute(<IncidentDetail />) },
      { path: 'inspect/:engineId/:instanceId', element: lazyRoute(<InspectPage />) },
      // Case Inspector Phase 2: the polymorphic CMMN sibling of /inspect (read-only, 6.8+).
      { path: 'case/:engineId/:caseInstanceId', element: lazyRoute(<CasePage />) },
      { path: 'audit', element: lazyRoute(<AuditLogPage />) },
      { path: 'admin/engines', element: lazyRoute(<AdminEnginesPage />) },
      { path: 'admin/access', element: lazyRoute(<AdminAccessPage />) },
      { path: 'admin/remediation-demand', element: lazyRoute(<RemediationDemandPage />) },
      {
        path: 'definitions/:engineId/:key/versions',
        element: lazyRoute(<DefinitionVersionsPage />),
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

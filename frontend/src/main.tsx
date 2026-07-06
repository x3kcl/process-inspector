import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Navigate, RouterProvider, createBrowserRouter, useSearchParams } from 'react-router'
import { ApiError } from './api/client'
import { InspectPage } from './inspect/InspectPage'
import { AuditLogPage } from './ops/AuditLogPage'
import { SearchPage } from './search/SearchPage'
import { hasSearch } from './search/urlState'
import { Shell } from './shell/Shell'
import { TriagePage } from './triage/TriagePage'
import './styles.css'

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
      { path: 'audit', element: <AuditLogPage /> },
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

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider } from 'react-router'
import App from './App'
import { ApiError } from './api/client'
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

// Router because the URL IS the search state (SPEC §4 Stage 1) — not for page routing (yet).
const router = createBrowserRouter([{ path: '/', element: <App /> }])

const rootElement = document.getElementById('root')
if (rootElement === null) throw new Error('missing #root element')

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)

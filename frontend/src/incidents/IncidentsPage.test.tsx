// @vitest-environment jsdom
// Issue #308: the server hard-caps GET /api/incidents and reports it honestly via
// `truncated` — the frontend must never render a capped list without the badge (ARCHITECTURE
// §2.3), and must NOT show the badge when the response is complete.
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { IncidentSummary } from '../api/model'
import { IncidentsPage } from './IncidentsPage'

const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: { GET: (...args: unknown[]) => get(...args) as unknown },
  }
})

afterEach(() => {
  cleanup()
  get.mockReset()
})

function incident(overrides: Partial<IncidentSummary> & { id: number }): IncidentSummary {
  return {
    signatureHash: `sig-${String(overrides.id)}`,
    lastSeen: '2026-07-18T00:00:00Z',
    state: 'OPEN',
    currentGeneration: true,
    ...overrides,
  }
}

function stub(items: IncidentSummary[], truncated: boolean) {
  get.mockImplementation((path: string) => {
    if (path === '/api/engines') {
      return Promise.resolve({ data: [], error: undefined, response: { status: 200 } })
    }
    if (path === '/api/incidents') {
      return Promise.resolve({
        data: { items, truncated },
        error: undefined,
        response: { status: 200 },
      })
    }
    throw new Error(`unexpected path in test: ${path}`)
  })
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <IncidentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('IncidentsPage truncation badge (#308)', () => {
  it('shows the truncation banner when the server hard-cap dropped rows', async () => {
    stub([incident({ id: 1 })], true)

    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('alert')).not.toBeNull()
    })
    expect(screen.getByText(/most recently seen incidents/)).not.toBeNull()
    expect(screen.getByText(/the ledger holds more/)).not.toBeNull()
  })

  it('renders no truncation banner when the list is complete', async () => {
    stub([incident({ id: 1 }), incident({ id: 2, state: 'RESOLVED' })], false)

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Incident Ledger')).not.toBeNull()
    })
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

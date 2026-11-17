// @vitest-environment jsdom
// #664: the intro pointed to "the Stage-0 triage cards above" — there are none on /incidents
// (they live on the home page) — and showed the internal register ID R-BAU-10.
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { IncidentsPage } from './IncidentsPage'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: {
      GET: (path: string) =>
        Promise.resolve({
          data: path === '/api/incidents' ? { items: [], truncated: false } : [],
          error: undefined,
          response: { status: 200 },
        }),
    },
  }
})

afterEach(cleanup)

it('names the home page cards by their visible title and drops internal IDs', async () => {
  // Given / When
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <IncidentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )

  // Then
  const intro = await screen.findByText(/^Persisted history for every failure class ever seen/)
  expect(intro.textContent).toContain('the live “Failures by error class” cards on the home page')
  expect(intro.textContent).not.toMatch(/R-BAU-10|Stage-0|cards above/)
  expect(screen.getByRole('link', { name: 'home page' }).getAttribute('href')).toBe('/')
})

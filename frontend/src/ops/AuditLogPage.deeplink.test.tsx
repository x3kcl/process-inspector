// @vitest-environment jsdom
// #662: the incident episode row links to /audit?since=<episode start>; the Ops log must open
// already filtered to that window start.
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeAll, expect, it, vi } from 'vitest'
import { AuditLogPage } from './AuditLogPage'

const getMock = vi.fn().mockResolvedValue({ data: [], error: undefined, response: { status: 200 } })
vi.mock('../api/client', () => ({
  api: { GET: (...args: unknown[]) => getMock(...args) as unknown },
  ApiError: class extends Error {},
}))
vi.mock('../api/me', () => ({ useMe: () => ({ data: { username: 'k.meier' } }) }))
vi.mock('../api/meta', () => ({ useTicketUrlTemplate: () => undefined }))

beforeAll(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  )
})

it('seeds and applies the since filter from the URL', async () => {
  // Given / When
  render(
    <MemoryRouter initialEntries={['/audit?since=2026-08-09T10%3A00%3A00Z']}>
      <QueryClientProvider client={new QueryClient()}>
        <AuditLogPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )

  // Then: the request carries it, and the visible filter field shows it.
  await waitFor(() => {
    expect(getMock).toHaveBeenCalledWith('/api/audit', {
      params: { query: { limit: 500, since: '2026-08-09T10:00:00Z' } },
    })
  })
  expect(screen.getByDisplayValue('2026-08-09T10:00:00Z')).toBeTruthy()
})

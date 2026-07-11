// @vitest-environment jsdom
// Usability W3-1 audit-surface slivers on the operations log:
//  - R-AUD-08: an "Export CSV" button in the toolbar, wired to the APPLIED filter state
//    (the M7 tester's located expectation: "next to the Actor/Action/Ticket/Since filters").
//  - R-AUD-09: the static attribution caveat on the ops-log header.
//  - R-AUD-05: the "My shift" preset (current user, last 8h) + "Copy shift report"
//    producing plain text with UNKNOWNs first under NEEDS VERIFICATION.
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { AuditEntryDto } from '../api/model'
import { AuditLogPage } from './AuditLogPage'

const rows: AuditEntryDto[] = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    actor: 'k.meier',
    action: 'retry-job',
    engineId: 'engine-a',
    instanceId: 'pi-9',
    ts: '2026-07-11T05:00:00Z',
    outcome: 'unknown',
    reason: 'stuck after hotfix',
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    actor: 'k.meier',
    action: 'suspend',
    engineId: 'engine-a',
    instanceId: 'pi-3',
    ts: '2026-07-11T03:00:00Z',
    outcome: 'ok',
    reason: 'holding for the tax-service fix',
  },
]

const getMock = vi
  .fn()
  .mockResolvedValue({ data: rows, error: undefined, response: { status: 200 } })
vi.mock('../api/client', () => ({
  api: { GET: (...args: unknown[]) => getMock(...args) as unknown },
  ApiError: class extends Error {},
}))
vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'k.meier' } }),
}))
vi.mock('../api/meta', () => ({
  useTicketUrlTemplate: () => undefined,
}))
const downloadMock = vi.fn().mockResolvedValue(undefined)
vi.mock('./exportCsv', () => ({
  downloadOperationsCsv: (...args: unknown[]) => downloadMock(...args) as Promise<void>,
}))

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

afterEach(() => {
  cleanup()
  getMock.mockClear()
  downloadMock.mockClear()
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <AuditLogPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('AuditLogPage W3-1 audit-surface slivers', () => {
  it('shows the attribution caveat on the ops-log header (R-AUD-09)', async () => {
    renderPage()
    await waitFor(() => screen.getByText(/authoritative WHO/))
    expect(screen.getByText(/shared service account/).textContent).toContain(
      'this log is the authoritative WHO',
    )
  })

  it('offers Export CSV in the toolbar, wired to the applied filters (R-AUD-08)', async () => {
    renderPage()
    const exportButton = await screen.findByRole('button', { name: /Export CSV/ })

    // Draft-only edits must NOT leak into the export — it mirrors what the grid shows.
    fireEvent.change(screen.getByLabelText(/Actor/), { target: { value: 'draft-only' } })
    fireEvent.click(exportButton)
    expect(downloadMock).toHaveBeenCalledWith({ actor: '', action: '', ticketId: '', since: '' })

    fireEvent.click(screen.getByRole('button', { name: /^Apply$/ }))
    fireEvent.click(exportButton)
    expect(downloadMock).toHaveBeenLastCalledWith(expect.objectContaining({ actor: 'draft-only' }))
  })

  it('the "My shift" preset filters to the signed-in user since shift start (R-AUD-05)', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /My shift/ }))

    const actorInput = screen.getByLabelText<HTMLInputElement>(/Actor/)
    expect(actorInput.value).toBe('k.meier')
    const sinceInput = screen.getByLabelText<HTMLInputElement>(/Since/)
    expect(sinceInput.value).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/)

    await waitFor(() => {
      const lastQuery = getMock.mock.calls.at(-1)?.[1] as {
        params: { query: Record<string, string> }
      }
      expect(lastQuery.params.query['actor']).toBe('k.meier')
      expect(lastQuery.params.query['since']).toBe(sinceInput.value)
    })
  })

  it('"Copy shift report" copies plain text with NEEDS VERIFICATION first (R-AUD-05)', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /Copy shift report/ }))
    await waitFor(() => {
      expect(writeText).toHaveBeenCalled()
    })
    const text = writeText.mock.calls[0][0] as string
    expect(text.indexOf('NEEDS VERIFICATION')).toBeGreaterThan(-1)
    expect(text.indexOf('pi-9')).toBeLessThan(text.indexOf('pi-3'))
    expect(text).toContain('2026-07-11T05:00:00.000Z')
  })
})

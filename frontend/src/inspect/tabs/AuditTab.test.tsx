// @vitest-environment jsdom
// R-AUD-09 (usability W3-1): the attribution caveat is STATIC on the instance Audit &
// Notes tab — it renders before/without any audit rows, because the trap it warns about
// (engine-side history blaming the shared service account) exists independently of them.
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AuditTab from './AuditTab'

vi.mock('../../api/queries', () => ({
  fetchInstanceAudit: vi.fn().mockResolvedValue([]),
  fetchInstanceNotes: vi.fn().mockResolvedValue([]),
  createInstanceNote: vi.fn(),
}))
vi.mock('../../api/meta', () => ({
  useTicketUrlTemplate: () => undefined,
}))

afterEach(cleanup)

describe('AuditTab attribution caveat (R-AUD-09)', () => {
  it('renders the SPEC §9 caveat unconditionally, info-tone', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <AuditTab engineId="engine-a" instanceId="pi-1" />
      </QueryClientProvider>,
    )

    const caveat = screen.getByText(/shared service account/)
    expect(caveat.textContent).toContain('this log is the authoritative WHO')
    expect(caveat.getAttribute('role')).toBe('note')
    expect(caveat.className).toContain('action-hint-info')
    // Static: present even with zero audit rows (the query resolves empty).
    expect(await screen.findByText(/No corrective actions recorded/)).toBeTruthy()
    expect(screen.getByText(/shared service account/)).toBeTruthy()
  })
})

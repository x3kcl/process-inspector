// @vitest-environment jsdom
// Issue #106 S0: the load-bearing behaviors are the honest no-demand verdict on an
// insufficient span, the fired-trigger verdict once both R-GOV-08 conditions hold, and the
// greyed 403 message for a non-ADMIN caller.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RemediationDemandPage } from './RemediationDemandPage'

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

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RemediationDemandPage />
    </QueryClientProvider>,
  )
}

describe('RemediationDemandPage (#106 S0)', () => {
  it('shows the honest not-fired verdict when the pilot span is still too short', async () => {
    get.mockResolvedValue({
      data: {
        dataSpanStart: '2026-06-01T00:00:00Z',
        dataSpanEnd: '2026-07-01T00:00:00Z',
        dataSpanDays: 30,
        spanSufficient: false,
        scannedRows: 42,
        truncated: false,
        sequences: [
          {
            verbs: ['retry-job', 'suspend'],
            instanceCount: 15,
            sampleInstances: ['engine-a:p-1'],
            meetsThreshold: true,
          },
        ],
        demandTriggerFired: false,
      },
      error: undefined,
      response: { status: 200 },
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/Trigger not fired/)).not.toBeNull()
    })
    expect(screen.getByText(/Not enough pilot history yet/)).not.toBeNull()
    // The instance count alone clears the bar — the span-insufficient copy must win, not a
    // conflicting "fired" reading of the qualifying sequence sitting right below it.
    expect(screen.getByText(/retry-job → suspend/)).not.toBeNull()
  })

  it('shows the fired verdict once both R-GOV-08 conditions hold', async () => {
    get.mockResolvedValue({
      data: {
        dataSpanStart: '2026-01-01T00:00:00Z',
        dataSpanEnd: '2026-07-01T00:00:00Z',
        dataSpanDays: 180,
        spanSufficient: true,
        scannedRows: 500,
        truncated: false,
        sequences: [
          {
            verbs: ['retry-job', 'suspend'],
            instanceCount: 12,
            sampleInstances: ['engine-a:p-1'],
            meetsThreshold: true,
          },
        ],
        demandTriggerFired: true,
      },
      error: undefined,
      response: { status: 200 },
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/Trigger fired/)).not.toBeNull()
    })
    expect(screen.getByText('yes')).not.toBeNull() // the meets-threshold cell
  })

  it('flags a truncated scan instead of silently understating the picture', async () => {
    get.mockResolvedValue({
      data: {
        dataSpanStart: '2026-01-01T00:00:00Z',
        dataSpanEnd: '2026-07-01T00:00:00Z',
        dataSpanDays: 180,
        spanSufficient: true,
        scannedRows: 50000,
        truncated: true,
        sequences: [],
        demandTriggerFired: false,
      },
      error: undefined,
      response: { status: 200 },
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/truncated, may undercount/)).not.toBeNull()
    })
  })

  it('greys the whole page with a clear reason for a non-ADMIN caller', async () => {
    get.mockResolvedValue({
      data: undefined,
      error: { detail: 'forbidden' },
      response: { status: 403 },
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/Requires the/)).not.toBeNull()
    })
    expect(screen.getByText(/ADMIN/)).not.toBeNull()
  })
})

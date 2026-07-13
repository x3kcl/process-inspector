// @vitest-environment jsdom
// Tier-4 destructive-bulk wizard (issue #100): the load-bearing behaviors are auto-preview on
// open, the per-engine scope readout, a dev-only scope needing no typed count, a PROD scope
// gating the submit button behind the resolved count, and a count-drift response surfacing the
// "refresh the scope" recovery instead of a generic error.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SearchRequest } from '../api/model'
import { OpsDrawerProvider } from '../ops/drawerState'
import { DestructiveBulkWizard } from './DestructiveBulkWizard'

const post = vi.fn()
const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: {
      POST: (...args: unknown[]) => post(...args) as unknown,
      GET: (...args: unknown[]) => get(...args) as unknown,
    },
  }
})

const CRITERIA: SearchRequest = { statuses: ['ACTIVE'], processDefinitionKey: 'payment' }

afterEach(() => {
  cleanup()
  post.mockReset()
  get.mockReset()
})

function renderWizard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  get.mockResolvedValue({
    data: { username: 'admin' },
    error: undefined,
    response: { status: 200 },
  })
  return render(
    <QueryClientProvider client={client}>
      <OpsDrawerProvider>
        <DestructiveBulkWizard
          criteria={CRITERIA}
          engines={[{ id: 'dev-engine', name: 'Dev Engine', environment: 'dev' }]}
          onClose={vi.fn()}
          onSubmitted={vi.fn()}
        />
      </OpsDrawerProvider>
    </QueryClientProvider>,
  )
}

describe('DestructiveBulkWizard (#100)', () => {
  it('auto-previews on open and shows the per-engine scope readout', async () => {
    post.mockResolvedValue({
      data: {
        count: 3,
        perEngineCounts: { 'dev-engine': 3 },
        sampleRows: [],
        capped: false,
        prodInScope: false,
      },
      error: undefined,
      response: { status: 200 },
    })

    renderWizard()

    await waitFor(() => {
      expect(post).toHaveBeenCalled()
    })
    const call = post.mock.calls.find(([path]) => path === '/api/bulk/destructive/preview')
    expect(call?.[1]).toMatchObject({ body: { criteria: CRITERIA } })
    expect(await screen.findByText('3')).not.toBeNull()
    expect(screen.getByText(/Dev Engine: 3/)).not.toBeNull()
  })

  it('a dev-only scope needs no typed count to submit', async () => {
    post.mockImplementation((path: string) => {
      if (path === '/api/bulk/destructive/preview') {
        return Promise.resolve({
          data: {
            count: 2,
            perEngineCounts: { 'dev-engine': 2 },
            sampleRows: [],
            capped: false,
            prodInScope: false,
          },
          error: undefined,
          response: { status: 200 },
        })
      }
      return Promise.resolve({
        data: { id: 'job-1', totalItems: 2 },
        error: undefined,
        response: { status: 200 },
      })
    })

    renderWizard()

    await screen.findByText('2')
    fireEvent.change(screen.getByLabelText(/Why are you terminating/), {
      target: { value: 'confirmed decommission, ticket ops-1' },
    })

    const submitButton = screen.getByRole('button', { name: /Terminate/ })
    expect(submitButton.hasAttribute('disabled')).toBe(false)
    fireEvent.click(submitButton)

    await waitFor(() => {
      expect(post.mock.calls.some(([path]) => path === '/api/bulk/destructive')).toBe(true)
    })
    const call = post.mock.calls.find(([path]) => path === '/api/bulk/destructive')
    expect(call?.[1]).toMatchObject({ body: { confirmedCount: undefined } })
  })

  it('a PROD scope disables submit until the resolved count is typed', async () => {
    post.mockResolvedValue({
      data: {
        count: 5,
        perEngineCounts: { 'prod-engine': 5 },
        sampleRows: [],
        capped: false,
        prodInScope: true,
      },
      error: undefined,
      response: { status: 200 },
    })

    renderWizard()

    await screen.findByText(/prod-engine: 5/)
    fireEvent.change(screen.getByLabelText(/Why are you terminating/), {
      target: { value: 'confirmed decommission, ticket ops-1' },
    })

    const submitButton = screen.getByRole('button', { name: /Terminate/ })
    expect(submitButton.hasAttribute('disabled')).toBe(true)

    fireEvent.change(screen.getByLabelText(/Type/), { target: { value: '5' } })
    expect(submitButton.hasAttribute('disabled')).toBe(false)
  })

  it('a count-drift response offers a refresh, not a bare error', async () => {
    post.mockImplementation((path: string) => {
      if (path === '/api/bulk/destructive/preview') {
        return Promise.resolve({
          data: {
            count: 5,
            perEngineCounts: { 'prod-engine': 5 },
            sampleRows: [],
            capped: false,
            prodInScope: true,
          },
          error: undefined,
          response: { status: 200 },
        })
      }
      return Promise.resolve({
        data: undefined,
        error: {
          code: 'bulk-count-drift',
          detail:
            'The scope now resolves to 3 instances (you confirmed 5) — review the fresh scope and retype the count.',
          outcome: 'refused',
          confirmedCount: 5,
          actualCount: 3,
        },
        response: { status: 409 },
      })
    })

    renderWizard()

    await screen.findByText(/prod-engine: 5/)
    fireEvent.change(screen.getByLabelText(/Why are you terminating/), {
      target: { value: 'confirmed decommission, ticket ops-1' },
    })
    fireEvent.change(screen.getByLabelText(/Type/), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: /Terminate/ }))

    expect(await screen.findByRole('button', { name: /Refresh the scope/ })).not.toBeNull()
  })
})

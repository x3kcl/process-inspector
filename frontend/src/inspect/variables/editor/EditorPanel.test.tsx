// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../../../components/toast'
import type { EngineDto } from '../../../api/model'
import { fetchInstanceJobs, fetchInstanceVariable } from '../../../api/queries'
import { EditorPanel } from './EditorPanel'

const action = vi.hoisted(() => ({
  mutate: vi.fn(),
  reset: vi.fn(),
  isPending: false,
  error: null,
}))

vi.mock('../../../api/queries', () => ({
  fetchInstanceVariable: vi.fn(),
  fetchInstanceJobs: vi.fn(),
}))

vi.mock('../../../api/actions', () => ({
  useInstanceAction: () => action,
  dispatchInstanceAction: vi.fn().mockResolvedValue({ deltaStatement: 'job moved' }),
  ActionError: class ActionError extends Error {},
}))

vi.mock('./SourceEditor', () => ({
  default: ({
    initialValue,
    onChange,
  }: {
    initialValue: string
    onChange: (buffer: string) => void
  }) => (
    <textarea
      aria-label="source buffer"
      defaultValue={initialValue}
      onChange={(event) => {
        onChange(event.target.value)
      }}
    />
  ),
}))

afterEach(() => {
  cleanup()
  action.mutate.mockReset()
  action.reset.mockReset()
})

const prod: EngineDto = { id: 'eng', name: 'Billing', environment: 'prod' }

function mount(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <ToastProvider>{ui}</ToastProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

function panel(overrides: Partial<Parameters<typeof EditorPanel>[0]> = {}) {
  return mount(
    <EditorPanel
      engineId="eng"
      instanceId="pi-1"
      entry={{ name: 'amount', scope: 'process', engineType: 'string', value: 'old' }}
      engine={prod}
      onClose={vi.fn()}
      {...overrides}
    />,
  )
}

describe('EditorPanel', () => {
  it('locks editing while the full value is in flight', () => {
    vi.mocked(fetchInstanceVariable).mockReturnValue(new Promise(() => undefined))
    panel()
    expect(screen.getByText(/Fetching the full current value/)).toBeTruthy()
  })

  it('stays locked and closes when the full value cannot be fetched', async () => {
    const onClose = vi.fn()
    vi.mocked(fetchInstanceVariable).mockRejectedValue(new Error('engine down'))
    panel({ onClose })
    expect((await screen.findByRole('alert')).textContent).toContain('editing stays locked')
    fireEvent.click(screen.getByRole('button', { name: 'close' }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('cancels without opening review', async () => {
    const onClose = vi.fn()
    vi.mocked(fetchInstanceVariable).mockResolvedValue({ value: 'old', type: 'string' })
    panel({ onClose })
    fireEvent.click(await screen.findByRole('button', { name: 'cancel' }))
    expect(onClose).toHaveBeenCalledOnce()
    expect(screen.queryByText('Verify the change')).toBeNull()
  })

  it('blocks review on a bad number and on invalid JSON', async () => {
    vi.mocked(fetchInstanceVariable).mockResolvedValue({ value: 1, type: 'long' })
    panel({ entry: { name: 'amount', scope: 'process', engineType: 'long', value: 1 } })
    const number = await screen.findByLabelText('new numeric value')
    fireEvent.change(number, { target: { value: 'nope' } })
    const review = screen.getByRole('button', { name: 'Review change…' })
    expect(review).toHaveProperty('disabled', true)
    expect(review.getAttribute('title')).toContain('not a number')
  })

  it('shows the JSON parse error and refuses to leave Source mode', async () => {
    vi.mocked(fetchInstanceVariable).mockResolvedValue({ value: { a: 1 }, type: 'json' })
    panel({ entry: { name: 'payload', scope: 'process', engineType: 'json', value: { a: 1 } } })
    fireEvent.click(await screen.findByRole('button', { name: 'Source' }))
    const buffer = await screen.findByLabelText('source buffer')
    fireEvent.change(buffer, { target: { value: '{' } })
    expect((await screen.findByRole('alert')).textContent).toMatch(/JSON|json|position|Expected/i)
    fireEvent.click(screen.getByRole('button', { name: 'Form' }))
    expect(screen.getByLabelText('source buffer')).toBeTruthy()
  })

  it('requires the prod reason, then submits and offers the dead-letter retry', async () => {
    const onClose = vi.fn()
    vi.mocked(fetchInstanceVariable).mockResolvedValue({ value: 'old', type: 'string' })
    vi.mocked(fetchInstanceJobs).mockResolvedValue({
      deadLetter: [{ id: 'job-9' }],
    })
    action.mutate.mockImplementation(
      (_input: unknown, opts: { onSuccess: (result: unknown) => void }) => {
        opts.onSuccess({ deltaStatement: 'amount updated' })
      },
    )
    panel({
      onClose,
      vitals: { status: 'FAILED', businessKey: 'ORDER-77' } as never,
    })
    fireEvent.change(await screen.findByLabelText('new text value'), { target: { value: 'new' } })
    fireEvent.click(screen.getByRole('button', { name: 'Review change…' }))
    expect(await screen.findByText('This is a PRODUCTION engine.')).toBeTruthy()
    const confirm = screen.getByRole('button', { name: /Change amount from/ })
    expect(confirm).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: 'operator fix' } })
    await waitFor(() => {
      expect(confirm).toHaveProperty('disabled', false)
    })
    fireEvent.click(confirm)
    expect(action.mutate).toHaveBeenCalledOnce()
    expect(onClose).toHaveBeenCalledOnce()
    fireEvent.click(await screen.findByRole('button', { name: 'Retry the failed job?' }))
    await waitFor(() => {
      expect(screen.getByText(/job moved/)).toBeTruthy()
    })
  })
})

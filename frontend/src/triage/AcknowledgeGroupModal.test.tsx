// @vitest-environment jsdom
// R-BAU-01 (usability W3-2): the acknowledge confirm — reason ≥10 inline gate (the W2
// convergence ruling: a refusal must carry a message, aria-invalid included), optional
// ticket (R-AUD-07 TicketField reuse, blank → undefined) and optional relative expiry
// (sent as an ISO instant). Un-acknowledge keeps the same reason discipline.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ErrorGroup } from '../api/model'
import { AcknowledgeGroupModal } from './AcknowledgeGroupModal'

const ackMutate = vi.fn()
const unackMutate = vi.fn()

vi.mock('../api/ack', () => ({
  useAcknowledgeErrorGroup: () => ({ mutate: ackMutate, isPending: false, error: null }),
  useUnacknowledgeErrorGroup: () => ({ mutate: unackMutate, isPending: false, error: null }),
}))

afterEach(cleanup)
beforeEach(() => {
  ackMutate.mockReset()
  unackMutate.mockReset()
})

const group: ErrorGroup = {
  signatureHash: 'sig-1',
  algoVersion: 1,
  exceptionClass: 'java.lang.ArithmeticException',
  normalizedMessage: 'divide by zero',
  total: 59,
  deadLetterCount: 59,
  retryingCount: 0,
  countsByEngine: {},
}

function renderModal(mode: 'acknowledge' | 'unacknowledge') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <AcknowledgeGroupModal group={group} mode={mode} environment="dev" onClose={vi.fn()} />
    </QueryClientProvider>,
  )
}

describe('AcknowledgeGroupModal (R-BAU-01)', () => {
  it('gates the confirm on a 10+ character reason, with a visible named refusal', () => {
    renderModal('acknowledge')
    const confirm = screen.getByRole('button', { name: 'Acknowledge group' })
    expect((confirm as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText(/Reason too short — 10\+ characters/)).toBeTruthy()
    const reason = screen.getByLabelText(/Why\?/)
    expect(reason.getAttribute('aria-invalid')).toBe('true')

    fireEvent.change(reason, { target: { value: 'known tax-service outage until 9am' } })
    expect((confirm as HTMLButtonElement).disabled).toBe(false)
    expect(reason.getAttribute('aria-invalid')).toBe('false')
  })

  it('submits coordinates + reason + ticket, and a preset expiry as an ISO instant', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-10T12:00:00Z'))
    try {
      renderModal('acknowledge')
      fireEvent.change(screen.getByLabelText(/Why\?/), {
        target: { value: 'known tax-service outage until 9am' },
      })
      fireEvent.change(screen.getByLabelText(/Ticket ID/), { target: { value: ' OPS-42 ' } })
      fireEvent.change(screen.getByLabelText(/Expiry/), { target: { value: '24h' } })
      fireEvent.click(screen.getByRole('button', { name: 'Acknowledge group' }))

      expect(ackMutate).toHaveBeenCalledTimes(1)
      expect(ackMutate.mock.calls[0][0]).toEqual({
        signatureHash: 'sig-1',
        algoVersion: 1,
        reason: 'known tax-service outage until 9am',
        ticketId: 'OPS-42',
        expiresAt: '2026-07-11T12:00:00.000Z',
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('a blank ticket and "no expiry" stay undefined — both are optional', () => {
    renderModal('acknowledge')
    fireEvent.change(screen.getByLabelText(/Why\?/), {
      target: { value: 'known tax-service outage until 9am' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge group' }))

    expect(ackMutate.mock.calls[0][0]).toMatchObject({
      ticketId: undefined,
      expiresAt: undefined,
    })
  })

  it('un-acknowledge demands the same reason discipline and sends coordinates only', () => {
    renderModal('unacknowledge')
    const confirm = screen.getByRole('button', { name: 'Un-acknowledge group' })
    expect((confirm as HTMLButtonElement).disabled).toBe(true)
    // No ticket/expiry inputs on the un-ack side.
    expect(screen.queryByLabelText(/Ticket ID/)).toBeNull()
    expect(screen.queryByLabelText(/Expiry/)).toBeNull()

    fireEvent.change(screen.getByLabelText(/Why\?/), {
      target: { value: 'outage resolved, un-muting' },
    })
    fireEvent.click(confirm)
    expect(unackMutate).toHaveBeenCalledTimes(1)
    expect(unackMutate.mock.calls[0][0]).toEqual({
      signatureHash: 'sig-1',
      algoVersion: 1,
      reason: 'outage resolved, un-muting',
    })
  })
})

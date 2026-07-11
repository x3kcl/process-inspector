// @vitest-environment jsdom
// R-AUD-07 (usability W3-1): the tier-3 destructive modal carries an OPTIONAL "Ticket ID"
// input next to the reason — it feeds ActionRequest.ticketId (TicketPolicy validates
// server-side; the audit surfaces linkify it). Blank stays undefined, never "".
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { VERBS } from './catalog'
import { DestructiveModal } from './DestructiveModal'

afterEach(cleanup)

function renderModal(onConfirm: (reason: string, ticketId?: string) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <DestructiveModal
        meta={VERBS.terminate}
        environment="dev"
        engineName="Engine A"
        target={<p>instance pi-1</p>}
        cascade={{ victims: [] }}
        expectedToken="pi-1"
        tokenName="instance id"
        confirmLabel="Terminate pi-1 permanently"
        pending={false}
        onConfirm={onConfirm}
        onClose={vi.fn()}
      />
    </QueryClientProvider>,
  )
}

describe('DestructiveModal ticket capture (R-AUD-07)', () => {
  it('threads a filled Ticket ID into onConfirm alongside the reason', () => {
    const onConfirm = vi.fn()
    renderModal(onConfirm)

    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'double-booked order, ops decision' },
    })
    fireEvent.change(screen.getByLabelText(/Ticket ID/), { target: { value: ' OPS-42 ' } })
    fireEvent.click(screen.getByRole('button', { name: /Terminate pi-1 permanently/ }))

    expect(onConfirm).toHaveBeenCalledWith('double-booked order, ops decision', 'OPS-42')
  })

  it('a blank ticket stays undefined — the field is optional', () => {
    const onConfirm = vi.fn()
    renderModal(onConfirm)

    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'double-booked order, ops decision' },
    })
    fireEvent.click(screen.getByRole('button', { name: /Terminate pi-1 permanently/ }))

    expect(onConfirm).toHaveBeenCalledWith('double-booked order, ops decision', undefined)
  })
})

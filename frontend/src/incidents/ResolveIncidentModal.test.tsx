// @vitest-environment jsdom
// #305: ResolveIncidentModal had zero component-test coverage, unlike every sibling
// mutating-action modal (ProtectModal, VerifyModal, AcknowledgeGroupModal). Mirrors
// ProtectModal.test.tsx's structure and mock-hook pattern: mock the mutation hook module
// itself (never a hand-rolled fetch mock), then prove confirm-gating, the exact request
// shape, the also-acknowledge opt-in, success→close (or hold-open-for-results), and that an
// API error surfaces instead of a silent close.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ActionError } from '../api/actions'
import { parseActionProblem } from '../actions/problem'
import type { components } from '../api/schema'

type IncidentSummary = components['schemas']['IncidentSummary']
type IncidentResolution = components['schemas']['IncidentResolution']

const resolveMutate = vi.fn()
let resolveError: ActionError | undefined
const useResolveIncidentSpy = vi.fn((id: number) => {
  void id // recorded via mock.calls for the "scoped to this incident id" assertion below
  return { mutate: resolveMutate, isPending: false, error: resolveError }
})
vi.mock('./useIncidents', () => ({
  useResolveIncident: (id: number) => useResolveIncidentSpy(id),
}))

import { ResolveIncidentModal } from './ResolveIncidentModal'

afterEach(() => {
  cleanup()
  resolveMutate.mockClear()
  useResolveIncidentSpy.mockClear()
  resolveError = undefined
})

const INCIDENT: IncidentSummary = {
  id: 42,
  exceptionClass: 'java.lang.ArithmeticException',
  normalizedMessage: 'divide by zero',
  state: 'OPEN',
}

function renderModal(onClose: () => void = vi.fn()) {
  render(<ResolveIncidentModal incident={INCIDENT} environment="dev" onClose={onClose} />)
}

function reasonField() {
  return screen.getByLabelText(/Why are you resolving this\?/)
}

function confirmButton() {
  return screen.getByRole<HTMLButtonElement>('button', { name: /^Resolve incident$/ })
}

describe('ResolveIncidentModal (#305)', () => {
  it('scopes the mutation hook to this incident id, and the mutation does not fire before confirm', () => {
    renderModal()
    expect(useResolveIncidentSpy).toHaveBeenCalledWith(42)
    expect(resolveMutate).not.toHaveBeenCalled()
  })

  it('gates the confirm button on a 10+ character reason, with a visible named refusal', () => {
    renderModal()
    const confirm = confirmButton()
    expect(confirm.disabled).toBe(true)
    expect(screen.getByText(/Reason too short — 10\+ characters/)).toBeTruthy()

    fireEvent.change(reasonField(), { target: { value: 'too short' } })
    expect(confirm.disabled).toBe(true)

    fireEvent.change(reasonField(), { target: { value: 'clearing a known false alarm' } })
    expect(confirm.disabled).toBe(false)
    expect(screen.queryByText(/Reason too short — 10\+ characters/)).toBeNull()
    expect(resolveMutate).not.toHaveBeenCalled()
  })

  it('submits the trimmed reason + ticketId + alsoAcknowledge in the exact request shape', () => {
    renderModal()
    fireEvent.change(reasonField(), { target: { value: '  clearing a known false alarm  ' } })
    fireEvent.change(screen.getByLabelText(/Ticket ID/), { target: { value: ' OPS-9 ' } })
    fireEvent.click(screen.getByLabelText(/Also acknowledge on the live dashboard/))
    fireEvent.click(confirmButton())

    expect(resolveMutate).toHaveBeenCalledTimes(1)
    expect(resolveMutate.mock.calls[0][0]).toEqual({
      reason: 'clearing a known false alarm',
      ticketId: 'OPS-9',
      alsoAcknowledge: true,
    })
  })

  it('a blank ticket stays undefined and also-acknowledge defaults to false (opt-in only)', () => {
    renderModal()
    fireEvent.change(reasonField(), { target: { value: 'clearing a known false alarm' } })
    fireEvent.click(confirmButton())

    expect(resolveMutate.mock.calls[0][0]).toEqual({
      reason: 'clearing a known false alarm',
      ticketId: undefined,
      alsoAcknowledge: false,
    })
  })

  it('on success with no also-acknowledge outcomes: toasts and closes immediately', () => {
    const onClose = vi.fn()
    renderModal(onClose)
    fireEvent.change(reasonField(), { target: { value: 'clearing a known false alarm' } })
    fireEvent.click(confirmButton())

    const [, options] = resolveMutate.mock.calls[0] as [
      unknown,
      { onSuccess: (resolution: IncidentResolution) => void },
    ]
    act(() => {
      options.onSuccess({ incident: INCIDENT, acknowledgements: [] })
    })

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('on success WITH also-acknowledge outcomes: holds the modal open and shows per-slice results instead of closing', () => {
    const onClose = vi.fn()
    renderModal(onClose)
    fireEvent.change(reasonField(), { target: { value: 'clearing a known false alarm' } })
    fireEvent.click(screen.getByLabelText(/Also acknowledge on the live dashboard/))
    fireEvent.click(confirmButton())

    const [, options] = resolveMutate.mock.calls[0] as [
      unknown,
      { onSuccess: (resolution: IncidentResolution) => void },
    ]
    act(() => {
      options.onSuccess({
        incident: INCIDENT,
        acknowledgements: [
          { engineId: 'engine-a', definitionKey: 'def-1', acknowledged: true },
          { engineId: 'engine-b', definitionKey: 'def-2', acknowledged: false, message: 'stale' },
        ],
      })
    })

    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByText(/Incident resolved\. Also-acknowledge outcomes/)).toBeTruthy()
    const items = screen.getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0]?.className).toBe('resolve-ack-ok')
    expect(items[0]?.textContent).toContain('engine-a')
    expect(items[0]?.textContent).toContain('def-1')
    expect(items[1]?.className).toBe('resolve-ack-failed')
    expect(items[1]?.textContent).toContain('stale')
    // The results screen only offers Close — never a second confirm.
    expect(screen.queryByRole('button', { name: /^Resolve incident$/ })).toBeNull()
  })

  it('an API error surfaces in the modal and does NOT silently close', () => {
    resolveError = new ActionError(
      parseActionProblem(409, {
        code: 'engine-rejected',
        detail: 'conflict',
        outcome: 'failed',
        engineBody: 'incident already resolved',
      }),
    )
    const onClose = vi.fn()
    renderModal(onClose)
    fireEvent.change(reasonField(), { target: { value: 'clearing a known false alarm' } })
    fireEvent.click(confirmButton())

    expect(screen.getByRole('alert').textContent).toMatch(/incident already resolved/)
    expect(onClose).not.toHaveBeenCalled()
    // The confirm button is still there — the operator can retry, not stuck on a dead screen.
    expect(screen.getByRole('button', { name: /^Resolve incident$/ })).toBeTruthy()
  })
})

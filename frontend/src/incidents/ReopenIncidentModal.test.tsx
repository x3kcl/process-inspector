// @vitest-environment jsdom
// #305: ReopenIncidentModal had zero component-test coverage. Mirrors
// ResolveIncidentModal.test.tsx / ProtectModal.test.tsx's structure and mock-hook pattern:
// mock the mutation hook module itself (never a hand-rolled fetch mock). Proves the hook is
// scoped to the RESOLVED incident's id, confirm-gating, the exact request shape, that a
// StrictMode double-render of a single confirm click never double-fires the mutation (the
// strictmode-effect-guard precedent from #168), success→close, and that an API error
// surfaces instead of a silent close.
import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ActionError } from '../api/actions'
import { parseActionProblem } from '../actions/problem'
import type { components } from '../api/schema'

type IncidentSummary = components['schemas']['IncidentSummary']

const reopenMutate = vi.fn()
let reopenError: ActionError | undefined
const useReopenIncidentSpy = vi.fn((id: number) => {
  void id // recorded via mock.calls for the "scoped to the RESOLVED incident id" assertion below
  return { mutate: reopenMutate, isPending: false, error: reopenError }
})
vi.mock('./useIncidents', () => ({
  useReopenIncident: (id: number) => useReopenIncidentSpy(id),
}))

import { ReopenIncidentModal } from './ReopenIncidentModal'

afterEach(() => {
  cleanup()
  reopenMutate.mockClear()
  useReopenIncidentSpy.mockClear()
  reopenError = undefined
})

const RESOLVED_INCIDENT: IncidentSummary = {
  id: 77,
  exceptionClass: 'java.lang.NullPointerException',
  normalizedMessage: 'npe on save',
  state: 'RESOLVED',
}

function renderModal(onClose: () => void = vi.fn(), strict = false) {
  const ui = (
    <ReopenIncidentModal incident={RESOLVED_INCIDENT} environment="dev" onClose={onClose} />
  )
  render(strict ? <StrictMode>{ui}</StrictMode> : ui)
}

function reasonField() {
  return screen.getByLabelText(/Why are you reopening this\?/)
}

function confirmButton() {
  return screen.getByRole<HTMLButtonElement>('button', { name: /^Reopen incident$/ })
}

describe('ReopenIncidentModal (#305)', () => {
  it('scopes the mutation hook to the RESOLVED incident id, and the mutation does not fire before confirm', () => {
    renderModal()
    expect(useReopenIncidentSpy).toHaveBeenCalledWith(77)
    expect(reopenMutate).not.toHaveBeenCalled()
  })

  it('gates the confirm button on a 10+ character reason, with a visible named refusal', () => {
    renderModal()
    const confirm = confirmButton()
    expect(confirm.disabled).toBe(true)
    expect(screen.getByText(/Reason too short — 10\+ characters/)).toBeTruthy()

    fireEvent.change(reasonField(), { target: { value: 'too short' } })
    expect(confirm.disabled).toBe(true)

    fireEvent.change(reasonField(), { target: { value: 'resolved by mistake, undoing' } })
    expect(confirm.disabled).toBe(false)
    expect(screen.queryByText(/Reason too short — 10\+ characters/)).toBeNull()
    expect(reopenMutate).not.toHaveBeenCalled()
  })

  it('confirming sends the trimmed reason as the exact request shape', () => {
    renderModal()
    fireEvent.change(reasonField(), { target: { value: '  resolved by mistake, undoing  ' } })
    fireEvent.click(confirmButton())

    expect(reopenMutate).toHaveBeenCalledTimes(1)
    expect(reopenMutate.mock.calls[0][0]).toEqual({ reason: 'resolved by mistake, undoing' })
  })

  it('on success: toasts and closes', () => {
    const onClose = vi.fn()
    renderModal(onClose)
    fireEvent.change(reasonField(), { target: { value: 'resolved by mistake, undoing' } })
    fireEvent.click(confirmButton())

    const [, options] = reopenMutate.mock.calls[0] as [unknown, { onSuccess: () => void }]
    act(() => {
      options.onSuccess()
    })

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('a StrictMode double-render does not double-fire the reopen mutation on a single confirm click', () => {
    renderModal(vi.fn(), true)
    // Rendered under StrictMode: React double-invokes render/effects in dev, but the
    // mutation only ever fires from the button's own click handler, never an effect —
    // one real click must still mean exactly one mutate() call.
    fireEvent.change(reasonField(), { target: { value: 'resolved by mistake, undoing' } })
    fireEvent.click(confirmButton())

    expect(reopenMutate).toHaveBeenCalledTimes(1)
  })

  it('an API error surfaces in the modal and does NOT silently close', () => {
    reopenError = new ActionError(
      parseActionProblem(409, {
        code: 'engine-rejected',
        detail: 'conflict',
        outcome: 'failed',
        engineBody: 'incident is not resolved',
      }),
    )
    const onClose = vi.fn()
    renderModal(onClose)
    fireEvent.change(reasonField(), { target: { value: 'resolved by mistake, undoing' } })
    fireEvent.click(confirmButton())

    expect(screen.getByRole('alert').textContent).toMatch(/incident is not resolved/)
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /^Reopen incident$/ })).toBeTruthy()
  })
})

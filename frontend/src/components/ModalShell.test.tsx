// @vitest-environment jsdom
// U2 (#88): the shared confirm-modal shell traps Tab focus inside the dialog and restores focus to
// whatever opened it — so a destructive modal can't leak keyboard focus (or a mis-click) to the
// page behind it, and dismissing it returns the operator where they were.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModalShell } from './ModalShell'

afterEach(cleanup)

function renderShell(onClose = vi.fn()) {
  render(
    <ModalShell
      title="Confirm"
      onClose={onClose}
      footer={
        <>
          <button type="button">Cancel</button>
          <button type="button">Confirm</button>
        </>
      }
    >
      <input aria-label="reason" />
    </ModalShell>,
  )
  // DOM order = focus order: [reason input, Cancel, Confirm].
  const input = screen.getByLabelText('reason')
  const [cancel, confirm] = screen.getAllByRole('button')
  return { input, cancel, confirm, onClose }
}

describe('ModalShell focus trap (U2, #88)', () => {
  it('focuses the cancel button on open (destructive-safe default)', () => {
    const { cancel } = renderShell()
    expect(document.activeElement).toBe(cancel)
  })

  it('Tab on the last focusable wraps to the first (never escapes the dialog)', () => {
    const { input, confirm } = renderShell()
    confirm.focus()
    fireEvent.keyDown(confirm, { key: 'Tab' })
    expect(document.activeElement).toBe(input)
  })

  it('Shift+Tab on the first focusable wraps to the last', () => {
    const { input, confirm } = renderShell()
    input.focus()
    fireEvent.keyDown(input, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(confirm)
  })

  it('Escape closes the modal', () => {
    const { cancel, onClose } = renderShell()
    fireEvent.keyDown(cancel, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('restores focus to the opener when the modal closes', () => {
    function Harness({ open }: { open: boolean }) {
      return (
        <>
          <button type="button" data-testid="opener">
            open
          </button>
          {open && (
            <ModalShell
              title="Confirm"
              onClose={vi.fn()}
              footer={<button type="button">Cancel</button>}
            >
              <input aria-label="reason" />
            </ModalShell>
          )}
        </>
      )
    }
    const { rerender } = render(<Harness open={false} />)
    const opener = screen.getByTestId('opener')
    opener.focus() // the element that "opens" the modal holds focus first
    expect(document.activeElement).toBe(opener)

    rerender(<Harness open={true} />) // modal mounts while `opener` is focused → captures it
    expect(document.activeElement).not.toBe(opener) // modal took focus (its Cancel button)

    rerender(<Harness open={false} />) // modal unmounts → focus handed back to the opener
    expect(document.activeElement).toBe(opener)
  })
})

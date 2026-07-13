// @vitest-environment jsdom
// Usability W2 #2 (theme T11, SPEC §5.0/§6): suspend/activate KEEP the single-click
// queue-state doctrine (no confirm added), but the button wears its REVERSIBLE badge
// visibly — reversibility must not live only in a hover title.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { VERBS } from './catalog'
import { InlineConfirm } from './InlineConfirm'

afterEach(cleanup)

describe('InlineConfirm reversibility badge (W2 #2, T11)', () => {
  it('wears the visible REVERSIBLE badge when asked — and stays single-click', () => {
    const onConfirm = vi.fn()
    render(
      <InlineConfirm
        meta={VERBS.suspend}
        gate={{ enabled: true }}
        confirmText="Suspend this case?"
        twoStep={false}
        pending={false}
        showReversibility
        onConfirm={onConfirm}
      />,
    )
    expect(screen.getByText('REVERSIBLE')).toBeTruthy()

    // §5.0 friction floor: queue-state verbs stay SINGLE-CLICK — one click confirms.
    fireEvent.click(screen.getByRole('button', { name: /Suspend/ }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('renders no badge by default (per-row job tables stay uncluttered)', () => {
    render(
      <InlineConfirm
        meta={VERBS.retryJob}
        gate={{ enabled: true }}
        confirmText="Retry job j1?"
        twoStep={false}
        pending={false}
        onConfirm={vi.fn()}
      />,
    )
    expect(screen.queryByText('RECOVERABLE')).toBeNull()
  })
})

describe('InlineConfirm focus restoration (#168 — a confirmed/cancelled armed control must not drop focus to <body>)', () => {
  it('confirming a two-step verb returns focus to the base button, not <body>', () => {
    render(
      <InlineConfirm
        meta={VERBS.retryJob}
        gate={{ enabled: true }}
        confirmText="Retry job j1?"
        twoStep={true}
        pending={false}
        onConfirm={vi.fn()}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /Retry/ }))
    const confirmButton = screen.getByRole('button', { name: 'Retry job j1?' })
    confirmButton.focus()
    fireEvent.click(confirmButton)

    // Armed → base is a structurally different subtree (React unmounts the armed button) — the
    // base button must have received focus, never the browser's unmount default of <body>.
    expect(document.activeElement?.tagName).toBe('BUTTON')
    expect(document.activeElement).not.toBe(document.body)
  })

  it('cancelling a two-step verb also returns focus to the base button', () => {
    render(
      <InlineConfirm
        meta={VERBS.retryJob}
        gate={{ enabled: true }}
        confirmText="Retry job j1?"
        twoStep={true}
        pending={false}
        onConfirm={vi.fn()}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /Retry/ }))
    const cancelButton = screen.getByRole('button', { name: 'cancel' })
    cancelButton.focus()
    fireEvent.click(cancelButton)

    expect(document.activeElement?.tagName).toBe('BUTTON')
    expect(document.activeElement).not.toBe(document.body)
  })
})

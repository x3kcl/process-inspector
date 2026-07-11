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

// @vitest-environment jsdom
// #743 review: /inspect/:engineId/:instanceId keeps the same component tree when only the instance
// changes (the "parent instance" link, the hierarchy tab), so a revealed value must not survive a
// change of target — neither one already shown nor one still in flight.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { revealVariable } from '../../api/reveal'
import { RevealButton } from './RevealButton'

vi.mock('../../api/reveal', () => ({ revealVariable: vi.fn() }))

const SECRET = 'tok-743-nav'
const A = { kind: 'instance' as const, engineId: 'eng1', targetId: 'inst-A' }
const B = { kind: 'instance' as const, engineId: 'eng1', targetId: 'inst-B' }

afterEach(() => {
  cleanup()
  vi.mocked(revealVariable).mockReset()
})

describe('RevealButton across a navigation to another instance', () => {
  it('drops a shown value when the target changes', async () => {
    vi.mocked(revealVariable).mockResolvedValue({ name: 'apiToken', type: 'string', value: SECRET })
    const view = render(<RevealButton target={A} name="apiToken" prod={false} gateReason={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Reveal apiToken' }))
    expect(await screen.findByText(SECRET)).toBeTruthy()

    view.rerender(<RevealButton target={B} name="apiToken" prod={false} gateReason={null} />)

    expect(screen.queryByText(SECRET)).toBeNull()
    expect(screen.getByRole('button', { name: 'Reveal apiToken' })).toBeTruthy()
  })

  it('never shows a value that arrives after the target changed', async () => {
    let resolve: (v: { name: string; type: string; value: unknown }) => void = () => undefined
    vi.mocked(revealVariable).mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    const view = render(<RevealButton target={A} name="apiToken" prod={false} gateReason={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Reveal apiToken' }))
    view.rerender(<RevealButton target={B} name="apiToken" prod={false} gateReason={null} />)

    await act(async () => {
      resolve({ name: 'apiToken', type: 'string', value: SECRET })
      await Promise.resolve()
    })

    expect(screen.queryByText(SECRET)).toBeNull()
  })
})

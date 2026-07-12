// @vitest-environment jsdom
// #118 item 3: the per-engine health cards carried real status text (unreachable, alarms,
// lifecycle reason) but were plain unfocusable divs — a keyboard/screen-reader user could
// never reach them. tabIndex was added; this proves both card variants stay reachable.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EngineDto } from '../api/model'
import { HeaderStrip } from './HeaderStrip'

vi.mock('../api/useEngines', () => ({
  useEngines: vi.fn(),
}))

afterEach(() => {
  cleanup()
})

async function renderStrip(engines: EngineDto[]) {
  const { useEngines } = await import('../api/useEngines')
  vi.mocked(useEngines).mockReturnValue({
    data: engines,
    error: null,
    isPending: false,
  } as ReturnType<typeof useEngines>)
  render(<HeaderStrip />)
}

describe('HeaderStrip engine cards (SPEC §4 — U4/#118)', () => {
  it('a reachable engine card is keyboard-focusable', async () => {
    await renderStrip([{ id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }])
    const card = screen.getByText('Payments DEV').closest('.engine-card')
    expect(card).not.toBeNull()
    expect(card?.getAttribute('tabindex')).toBe('0')
  })

  it('an unreachable engine card is keyboard-focusable too', async () => {
    await renderStrip([
      {
        id: 'eng1',
        name: 'Payments DEV',
        environment: 'dev',
        reachable: false,
        healthError: 'connection refused',
      },
    ])
    const card = screen.getByText('Payments DEV').closest('.engine-card')
    expect(card).not.toBeNull()
    expect(card?.getAttribute('tabindex')).toBe('0')
    expect(card?.className).toContain('engine-card-down')
  })

  it('an inactive-lifecycle card (the disabled variant) is keyboard-focusable too', async () => {
    await renderStrip([
      { id: 'eng1', name: 'Payments DEV', environment: 'dev', lifecycle: 'disabled' },
    ])
    const card = screen.getByText('Payments DEV').closest('.engine-card')
    expect(card).not.toBeNull()
    expect(card?.getAttribute('tabindex')).toBe('0')
    expect(card?.className).toContain('engine-card-disabled')
  })
})

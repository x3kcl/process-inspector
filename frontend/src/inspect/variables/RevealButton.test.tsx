// @vitest-environment jsdom
// #743: the audited reveal in the SPA — a real "Reveal" button beside a hidden value, disabled with a
// visible reason for tiers that may not; on a prod engine a small dialog asks for the reason (≥10);
// the revealed value lives in component state only (never the TanStack Query cache, gone on
// unmount) and comes with a copy button.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { revealVariable } from '../../api/reveal'
import { buildLedger } from './ledger'
import { RevealButton } from './RevealButton'
import { VariableLedger } from './VariableLedger'

vi.mock('../../api/reveal', () => ({ revealVariable: vi.fn() }))

const SECRET = 'tok-743-spa'
const TARGET = { kind: 'instance' as const, engineId: 'eng1', targetId: 'inst-1' }

afterEach(() => {
  cleanup()
  vi.mocked(revealVariable).mockReset()
})

function mount(props: Partial<Parameters<typeof RevealButton>[0]> = {}) {
  const client = new QueryClient()
  const view = render(
    <QueryClientProvider client={client}>
      <RevealButton target={TARGET} name="apiToken" prod={false} gateReason={null} {...props} />
    </QueryClientProvider>,
  )
  return { client, ...view }
}

describe('RevealButton', () => {
  it('is a real button named for the variable; a click reveals the value with a copy button', async () => {
    vi.mocked(revealVariable).mockResolvedValue({ name: 'apiToken', type: 'string', value: SECRET })
    mount()

    fireEvent.click(screen.getByRole('button', { name: 'Reveal apiToken' }))

    expect(await screen.findByText(SECRET)).toBeTruthy()
    expect(screen.getByRole('button', { name: /copy/i })).toBeTruthy()
    expect(revealVariable).toHaveBeenCalledExactlyOnceWith(TARGET, 'apiToken', undefined, undefined)
  })

  it('is disabled with the visible reason for a tier that may not reveal', () => {
    mount({ gateReason: 'Requires OPERATOR — you are VIEWER' })

    const button = screen.getByRole('button', { name: 'Reveal apiToken' })
    expect(button).toHaveProperty('disabled', true)
    expect(screen.getByText(/Requires OPERATOR — you are VIEWER/)).toBeTruthy()
    fireEvent.click(button)
    expect(revealVariable).not.toHaveBeenCalled()
  })

  it('asks for a reason of at least 10 characters on a prod engine and sends it', async () => {
    vi.mocked(revealVariable).mockResolvedValue({ name: 'apiToken', type: 'string', value: SECRET })
    mount({ prod: true })

    fireEvent.click(screen.getByRole('button', { name: 'Reveal apiToken' }))
    const dialog = screen.getByRole('dialog')
    const input = screen.getByLabelText<HTMLInputElement>(/reason/i)
    const confirm = screen.getByRole('button', { name: 'Reveal on prod' })
    fireEvent.change(input, { target: { value: 'too short' } })
    expect(confirm).toHaveProperty('disabled', true)
    fireEvent.change(input, { target: { value: 'INC-743 rotate the token' } })
    fireEvent.click(confirm)

    expect(dialog).toBeTruthy()
    expect(await screen.findByText(SECRET)).toBeTruthy()
    expect(revealVariable).toHaveBeenCalledExactlyOnceWith(
      TARGET,
      'apiToken',
      undefined,
      'INC-743 rotate the token',
    )
  })

  it('keeps the value out of the query cache and drops it on unmount', async () => {
    vi.mocked(revealVariable).mockResolvedValue({ name: 'apiToken', type: 'string', value: SECRET })
    const { client, unmount } = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Reveal apiToken' }))
    expect(await screen.findByText(SECRET)).toBeTruthy()

    const cached = JSON.stringify([
      client
        .getQueryCache()
        .getAll()
        .map((query) => query.state.data),
      client
        .getMutationCache()
        .getAll()
        .map((mutation) => mutation.state.data),
    ])
    expect(cached).not.toContain(SECRET)
    unmount()
    mount()
    expect(screen.queryByText(SECRET)).toBeNull()
    expect(screen.getByRole('button', { name: 'Reveal apiToken' })).toBeTruthy()
  })

  it('shows the refusal, never a value, when the BFF refuses', async () => {
    vi.mocked(revealVariable).mockRejectedValue(new Error('At most 10 reveals per minute per user'))
    mount()

    fireEvent.click(screen.getByRole('button', { name: 'Reveal apiToken' }))

    expect((await screen.findByRole('alert')).textContent).toContain('At most 10 reveals')
  })
})

describe('the ledger', () => {
  it('offers the reveal on the masked row only', () => {
    const groups = buildLedger([
      {
        name: 'apiToken',
        engineType: 'string',
        value: undefined,
        scope: 'process',
        masked: true,
      } as never,
      { name: 'amount', engineType: 'integer', value: 42, scope: 'process' },
    ])
    render(
      <VariableLedger
        groups={groups}
        renderReveal={(row) => (
          <RevealButton target={TARGET} name={row.entry.name} prod={false} gateReason={null} />
        )}
      />,
    )

    expect(screen.getByRole('button', { name: 'Reveal apiToken' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Reveal amount' })).toBeNull()
  })
})

// @vitest-environment jsdom
// #236: the omnibox negative result ("was not found on any reachable engine" / "resolved
// against N of N engines") must disclose — in the same panel text — the registered engines
// the resolve never checked. A wrong "does not exist" answer closes a real customer's
// ticket, so the reader must see from this sentence alone that the check was not exhaustive.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EngineDto, ResolveResponse } from '../api/model'
import { Omnibox } from './Omnibox'

const resolveQuery = vi.hoisted(() => vi.fn<(q: string) => Promise<ResolveResponse>>())
vi.mock('../api/queries', () => ({ resolveQuery }))
vi.mock('../api/useEngines', () => ({ useEngines: vi.fn() }))

afterEach(cleanup)

async function submitGarbageId(engines: EngineDto[], response: ResolveResponse) {
  const { useEngines } = await import('../api/useEngines')
  vi.mocked(useEngines).mockReturnValue({ data: engines } as ReturnType<typeof useEngines>)
  resolveQuery.mockResolvedValue(response)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Omnibox />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'deadbeef-0000' } })
  fireEvent.submit(screen.getByRole('search'))
  return await screen.findByRole('region', { name: 'Resolve results' })
}

const ACTIVE_A: EngineDto = { id: 'engine-a', lifecycle: 'active', reachable: true }
const ACTIVE_B: EngineDto = { id: 'engine-b', lifecycle: 'active', reachable: true }

describe('Omnibox negative-result coverage disclosure (#236)', () => {
  it('names the registered-but-never-probed engines in the same panel text', async () => {
    const panel = await submitGarbageId(
      [
        ACTIVE_A,
        ACTIVE_B,
        { id: 'engine-c', lifecycle: 'disabled' },
        { id: 'engine-d', lifecycle: 'probe_failed', reachable: false },
      ],
      {
        query: 'deadbeef-0000',
        matches: [],
        perEngine: { 'engine-a': { ok: true }, 'engine-b': { ok: true } },
      },
    )
    // The negative sentence itself warns that the check was not exhaustive…
    expect(panel.textContent).toContain('was not found on any reachable engine')
    expect(panel.textContent).toContain('but some registered engines were not searched')
    // …and the honesty line counts AND names the excluded engines inline.
    expect(panel.textContent).toContain('resolved against 2 of 2 engines')
    expect(panel.textContent).toContain(
      '(2 more registered engines not checked: engine-c [disabled], engine-d [probe failed] — see Engines)',
    )
  })

  it('a genuinely exhaustive probe carries no spurious "more excluded" clause', async () => {
    const panel = await submitGarbageId([ACTIVE_A, ACTIVE_B], {
      query: 'deadbeef-0000',
      matches: [],
      perEngine: { 'engine-a': { ok: true }, 'engine-b': { ok: true } },
    })
    expect(panel.textContent).toContain('resolved against 2 of 2 engines')
    expect(panel.textContent).not.toContain('more registered')
    expect(panel.textContent).not.toContain('were not searched')
  })

  it('a failed probe stays on the unreachable list without double-counting as uncovered', async () => {
    const panel = await submitGarbageId(
      [ACTIVE_A, { id: 'engine-b', lifecycle: 'active', reachable: false }],
      {
        query: 'deadbeef-0000',
        matches: [],
        perEngine: { 'engine-a': { ok: true }, 'engine-b': { ok: false, error: 'timeout' } },
      },
    )
    expect(panel.textContent).toContain('resolved against 1 of 2 engines')
    expect(panel.textContent).toContain('engine-b unreachable (timeout)')
    expect(panel.textContent).not.toContain('more registered')
  })
})

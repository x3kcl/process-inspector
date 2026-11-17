// @vitest-environment jsdom
// Historic migration of one ended case (#500): greyed with the version reason on a 6.8 engine,
// one call on 7.x, and the typed case id on prod.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'admin' } }),
  roleOn: () => 'ADMIN',
}))

const post = vi.fn()
const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: {
      POST: (...args: unknown[]) => post(...args) as unknown,
      GET: (...args: unknown[]) => get(...args) as unknown,
    },
  }
})

import { CaseHistoricMigrate } from './CaseHistoricMigrate'

afterEach(() => {
  cleanup()
  post.mockReset()
  get.mockReset()
})

const ok = (data: unknown) => Promise.resolve({ data, error: undefined, response: { status: 200 } })
const GATE = "needs Flowable 7.x (engine 'engine-a' is 6.8.0)"
const PATH = '/api/cases/{engineId}/{caseInstanceId}/migrate-historic'

function renderControl(gate: string | undefined, environment: string) {
  get.mockImplementation((path: string) => {
    if (path === '/api/engines') return ok([{ id: 'engine-a', name: 'A', environment }])
    return ok({
      engineId: 'engine-a',
      key: 'demoCase',
      definitionRoutesGate: gate,
      versions: [
        { caseDefinitionId: 'def-2', version: 2, latest: true },
        { caseDefinitionId: 'def-1', version: 1, latest: false },
      ],
    })
  })
  post.mockImplementation(() => ok({ outcome: 'ok', deltaStatement: 'History re-pointed.' }))
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <CaseHistoricMigrate
        engineId="engine-a"
        caseInstanceId="case-7"
        caseDefinitionKey="demoCase"
        caseDefinitionId="def-1"
      />
    </QueryClientProvider>,
  )
}

describe('CaseHistoricMigrate (#500)', () => {
  it('is greyed with the version reason on a 6.8 engine', async () => {
    renderControl(GATE, 'dev')
    await screen.findByText(new RegExp('Historic case migration needs Flowable 7.x'))
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'history follows the fixed model' },
    })
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Migrate history' })
    expect(button.disabled).toBe(true)
    expect(button.getAttribute('aria-describedby')).toBe('historic-case-gate')
    expect(button.title).toBe(GATE)
    expect(post).not.toHaveBeenCalled()
  })

  it('on a 7.x prod engine needs the typed case id, then sends one call', async () => {
    renderControl(undefined, 'prod')
    const typed = await screen.findByLabelText(/Type the case id/)
    await screen.findByRole('option', { name: /v2 latest/ })
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'history follows the fixed model' },
    })
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Migrate history' })
    expect(button.disabled).toBe(true)
    fireEvent.change(typed, { target: { value: 'case-7' } })
    expect(button.disabled).toBe(false)
    fireEvent.click(button)

    await waitFor(() => {
      expect(post.mock.calls.some(([path]) => path === PATH)).toBe(true)
    })
    expect(post.mock.calls.find(([path]) => path === PATH)?.[1]).toEqual({
      params: { path: { engineId: 'engine-a', caseInstanceId: 'case-7' } },
      body: {
        toCaseDefinitionId: 'def-2',
        reason: 'history follows the fixed model',
        ticketId: undefined,
        confirmToken: 'case-7',
      },
    })
  })
})

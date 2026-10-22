// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DefinitionsPage } from './DefinitionsPage'

const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: { GET: (...args: unknown[]) => get(...args) as unknown },
  }
})

afterEach(() => {
  cleanup()
  get.mockReset()
})

function catalog(latest: boolean) {
  return {
    data: {
      countsBounded: false,
      perEngine: { 'engine-a': { ok: true, fetched: 1, total: 1 } },
      definitions: [
        {
          engineId: 'engine-a',
          kind: 'BPMN',
          definitionId: 'alpha:1:abc',
          key: 'alpha',
          name: 'Alpha',
          version: latest ? 2 : 1,
          latest,
          suspended: false,
          runningCount: 3,
          countLowerBound: false,
          protectedDefinition: false,
        },
      ],
    },
    error: undefined,
    response: { status: 200 },
  }
}

describe('DefinitionsPage', () => {
  it('toggles latest-only and drills to the definition and to search', async () => {
    get.mockImplementation(
      (path: string, init?: { params?: { query?: { latestOnly?: boolean } } }) => {
        const latest = init?.params?.query?.latestOnly !== false
        return Promise.resolve(catalog(latest))
      },
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <DefinitionsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    const open = await screen.findByRole('link', { name: 'Open' })
    expect(open.getAttribute('href')).toBe('/definitions/engine-a/alpha%3A1%3Aabc')
    expect(screen.getByRole('link', { name: 'Instances' }).getAttribute('href')).toBe(
      '/search?engineId=engine-a&processDefinitionId=alpha%3A1%3Aabc',
    )
    fireEvent.click(screen.getByRole('checkbox', { name: 'Latest version only' }))
    await waitFor(() => {
      const calls = get.mock.calls.map(
        (call) => call[1] as { params?: { query?: { latestOnly?: boolean } } },
      )
      expect(calls.some((call) => call.params?.query?.latestOnly === false)).toBe(true)
    })
  })
})

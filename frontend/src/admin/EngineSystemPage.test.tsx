// @vitest-environment jsdom
// Issue #627: the table section must render one row per table from what real 6.8 and 7.1
// engines answer. The payload is built from the recorded /management/tables fixtures the
// backend wire tests use, mapped the way EngineSystemInfoService maps them (name + count).
import { readFileSync } from 'node:fs'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EngineSystemPage } from './EngineSystemPage'

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

function recordedTables(major: string): { name: string; count: number }[] {
  const raw = readFileSync(
    `../backend/src/test/resources/wire/${major}/management-tables.json`,
    'utf8',
  )
  return (JSON.parse(raw) as { name: string; count: number }[]).map(({ name, count }) => ({
    name,
    count,
  }))
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/engines/engine-a/system']}>
        <Routes>
          <Route path="/admin/engines/:id/system" element={<EngineSystemPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('EngineSystemPage (#627)', () => {
  it.each(['6.8', '7.1'])('renders one row per table from a recorded %s answer', async (major) => {
    const tables = recordedTables(major)
    get.mockResolvedValue({
      data: {
        engineId: 'engine-a',
        version: major,
        properties: {},
        tables,
        failures: [],
        metricsStatus: 'disabled',
      },
      error: undefined,
      response: { status: 200 },
    })

    renderPage()

    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(tables.length)
    const procdef = tables.find((t) => t.name === 'ACT_RE_PROCDEF')
    expect(procdef).toBeDefined()
    const row = within(table).getByText('ACT_RE_PROCDEF').closest('tr')
    expect(row?.textContent).toBe(`ACT_RE_PROCDEF${String(procdef?.count)}`)
    expect(screen.queryByText(/Unavailable/)).toBeNull()
  })
})

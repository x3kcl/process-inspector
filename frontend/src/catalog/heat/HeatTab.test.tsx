// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HeatTab } from './HeatTab'

const get = vi.fn()
vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client')
  return { ...actual, api: { GET: (...args: unknown[]) => get(...args) as unknown } }
})

// bpmn-js cannot run in jsdom: the stub records the props and offers a click on a node.
let canvasProps: Record<string, unknown> = {}
vi.mock('../../inspect/DiagramCanvas', () => ({
  DiagramCanvas: (props: {
    selectedActivityId?: string
    onSelectActivity?: (id: string) => void
  }) => {
    canvasProps = props
    return (
      <div data-testid="canvas" data-selected={props.selectedActivityId ?? ''}>
        <button type="button" onClick={() => props.onSelectActivity?.('approve')}>
          canvas click approve
        </button>
      </div>
    )
  },
}))

afterEach(() => {
  cleanup()
  get.mockReset()
  canvasProps = {}
})

const node = (over: Record<string, unknown>) => ({
  activityId: 'x',
  name: 'X',
  type: 'userTask',
  executions: 5,
  instances: 5,
  hitPct: 50,
  armedNotTaken: 0,
  avgMs: 1200,
  p50Ms: 1000,
  p95Ms: 2000,
  maxMs: 2500,
  totalMs: 6000,
  sharePct: 30,
  tier: 'WARM',
  ...over,
})

const heat = (over: Record<string, unknown> = {}) => ({
  engineId: 'eng1',
  definitionId: 'order:2:b',
  asOf: '2026-11-18T10:00:00Z',
  cached: false,
  coverage: 'FULL',
  structure: 'RESOLVED',
  requestedN: 200,
  sampledInstances: 150,
  finishedTotal: 1000,
  droppedInstances: 0,
  dropReasons: { tooManyRows: 0, readFailed: 0, noActivityHistory: 0 },
  partial: false,
  nodes: [
    node({ activityId: 'approve', name: 'Approve', hitPct: 62, sharePct: 55, tier: 'DOMINANT' }),
    node({ activityId: 'ship', name: 'Ship', type: 'serviceTask', tier: 'COOL', sharePct: 0.5 }),
    node({ activityId: 'start', name: 'Start', type: 'startEvent', avgMs: 0, tier: 'NONE' }),
    node({
      activityId: 'catchB',
      name: 'Catch B',
      type: 'intermediateCatchEvent',
      executions: 0,
      instances: 1,
      armedNotTaken: 3,
      tier: 'NONE',
    }),
  ],
  outcomes: [
    { endEventId: 'endOk', name: 'Approved', count: 90, pct: 60 },
    { endEventId: 'endNo', name: 'Rejected', count: 50, pct: 33.3 },
  ],
  noEndEventCount: 10,
  samplingNote: 'newest 150 finished instances',
  ...over,
})

const ok = (data: unknown) => ({ data, error: undefined, response: { status: 200 } })
const refused = (status: number, code: string) => ({
  data: undefined,
  error: { code },
  response: { status },
})

function serve(responses: Record<string, () => unknown>) {
  get.mockImplementation((path: string, init?: { params?: { query?: unknown } }) => {
    if (path.endsWith('/xml')) return Promise.resolve(ok({ xml: '<definitions/>' }))
    const key = path.endsWith('/heat') ? 'heat' : path
    const query = init?.params?.query as { refresh?: boolean } | undefined
    return Promise.resolve(
      (responses[query?.refresh === true ? 'refresh' : key] ?? responses.heat)(),
    )
  })
}

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <HeatTab
        engineId="eng1"
        currentId="order:2:b"
        versions={[
          { definitionId: 'order:2:b', version: 2, latest: true },
          { definitionId: 'order:1:a', version: 1 },
        ]}
      />
    </QueryClientProvider>,
  )
}

const heatCalls = () =>
  get.mock.calls.filter(([path]) => String(path).endsWith('/heat')) as [
    string,
    { params: { path: { definitionId: string }; query: { n?: number; refresh?: boolean } } },
  ][]

describe('HeatTab', () => {
  it('asks for the default sample of the picked version and states the sample', async () => {
    serve({ heat: () => ok(heat()) })
    mount()
    expect(await screen.findByText(/sampled 150 of 1,000 finished instances/)).toBeTruthy()
    expect(heatCalls()[0]?.[1].params.query.n).toBe(200)
    expect(heatCalls()[0]?.[1].params.path.definitionId).toBe('order:2:b')
  })

  it('refetches for another version from the version picker', async () => {
    serve({ heat: () => ok(heat()) })
    mount()
    await screen.findByText(/sampled 150/)
    fireEvent.change(screen.getByLabelText('Version'), { target: { value: 'order:1:a' } })
    await waitFor(() => {
      expect(heatCalls().some(([, init]) => init.params.path.definitionId === 'order:1:a')).toBe(
        true,
      )
    })
  })

  it('offers sample sizes as presets only and refetches on a change', async () => {
    serve({ heat: () => ok(heat()) })
    mount()
    await screen.findByText(/sampled 150/)
    const select = screen.getByLabelText<HTMLSelectElement>('Sample size')
    expect([...select.options].map((option) => option.value)).toEqual(['200', '500', '1000'])
    expect(screen.queryByRole('spinbutton')).toBeNull()
    fireEvent.change(select, { target: { value: '500' } })
    await waitFor(() => {
      expect(heatCalls().some(([, i]) => i.params.query.n === 500)).toBe(true)
    })
  })

  it('hands the canvas text badges by tier: tasks yes, events and gateways no', async () => {
    serve({ heat: () => ok(heat()) })
    mount()
    await screen.findByText(/sampled 150/)
    await screen.findByTestId('canvas')
    const markers = (canvasProps.markers ?? {}) as Record<string, string[]>
    expect(markers.heatDominantActivityIds).toEqual(['approve'])
    expect(markers.heatCoolActivityIds).toContain('ship')
    const all = Object.values(markers).flat()
    expect(all).not.toContain('start')
    const texts = canvasProps.glyphTexts as Record<string, Map<string, string>>
    expect(texts.heatDominantActivityIds.get('approve')).toBe('avg 1.2 s · 62 % · dominant')
    expect(texts.heatCoolActivityIds.get('catchB')).toContain('armed in 4, taken in 1')
  })

  it('lists every node in a table with hit rate, average, p50, p95 and armed', async () => {
    serve({ heat: () => ok(heat()) })
    mount()
    const table = await screen.findByRole('table', { name: 'Node heat' })
    const row = within(table).getByRole('row', { name: /Approve/ })
    expect(row.textContent).toContain('62 %')
    expect(row.textContent).toContain('1.2 s')
    expect(row.textContent).toContain('1.0 s')
    expect(row.textContent).toContain('2.0 s')
    expect(within(table).getByRole('row', { name: /Catch B/ }).textContent).toContain('3')
    expect(within(table).getByRole('row', { name: /Start/ })).toBeTruthy()
  })

  it('syncs selection between the table and the canvas, both ways', async () => {
    serve({ heat: () => ok(heat()) })
    mount()
    const table = await screen.findByRole('table', { name: 'Node heat' })
    await screen.findByTestId('canvas')
    fireEvent.click(within(table).getByRole('button', { name: 'Show Ship on diagram' }))
    expect(screen.getByTestId('canvas').dataset.selected).toBe('ship')
    fireEvent.click(screen.getByText('canvas click approve'))
    expect(screen.getByTestId('canvas').dataset.selected).toBe('approve')
    expect(
      within(table)
        .getByRole('row', { name: /Approve/ })
        .getAttribute('aria-selected'),
    ).toBe('true')
  })

  it('shows the outcome funnel over named end events plus the no-end-event row', async () => {
    serve({ heat: () => ok(heat()) })
    mount()
    const funnel = await screen.findByRole('list', { name: 'Outcome funnel' })
    expect(funnel.textContent).toContain('Approved')
    expect(funnel.textContent).toContain('90')
    expect(funnel.textContent).toContain('60 %')
    expect(funnel.textContent).toContain('no end event recorded')
    expect(funnel.textContent).toContain('10')
  })

  it('shows the partial badge and the drop reasons when instances were dropped', async () => {
    serve({
      heat: () =>
        ok(
          heat({
            partial: true,
            droppedInstances: 4,
            dropReasons: { tooManyRows: 3, readFailed: 1, noActivityHistory: 0 },
          }),
        ),
    })
    mount()
    expect(await screen.findByText('partial')).toBeTruthy()
    expect(screen.getByText(/4 dropped/)).toBeTruthy()
  })

  it('shows the coverage message instead of zeros for no activity history', async () => {
    serve({ heat: () => ok(heat({ coverage: 'NO_ACTIVITY_HISTORY', nodes: [], outcomes: [] })) })
    mount()
    expect(await screen.findByText(/records no activity history/)).toBeTruthy()
    expect(screen.queryByRole('table', { name: 'Node heat' })).toBeNull()
  })

  it('says no finished instances yet when there is no history to read', async () => {
    serve({
      heat: () =>
        ok(heat({ coverage: 'NO_HISTORY', sampledInstances: 0, finishedTotal: 0, nodes: [] })),
    })
    mount()
    expect(await screen.findByText(/No finished instances yet/)).toBeTruthy()
  })

  it('refreshes with a real button and shows the server throttle answer', async () => {
    serve({
      heat: () => ok(heat()),
      refresh: () => ok(heat({ cached: true, refreshThrottledUntil: '2026-11-18T10:01:00Z' })),
    })
    mount()
    await screen.findByText(/sampled 150/)
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText(/next refresh possible at/)).toBeTruthy()
    expect(heatCalls().some(([, init]) => init.params.query.refresh === true)).toBe(true)
  })

  it.each([
    [404, 'definition-not-found', /no longer exists/],
    [400, 'heat-bpmn-only', /BPMN process definitions only/],
    [503, 'engine-unavailable', /did not answer/],
    [503, 'heat-unavailable', /could not be read/],
  ])('answers %s %s with its own message', async (status, code, message) => {
    serve({ heat: () => refused(status, code) })
    mount()
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(message)
  })
})

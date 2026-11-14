// @vitest-environment jsdom
// Hierarchy migration plan builder (#497): the plan is created for the root, every source
// definition is configured (target + mapping) or skipped, and the submit carries the stored
// plan id only. Statuses are the plan API's; the screen decides nothing itself.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OpsDrawerProvider } from '../ops/drawerState'
import { HierarchyMigrateModal } from './HierarchyMigrateModal'

const post = vi.fn()
const get = vi.fn()
const put = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: {
      POST: (...args: unknown[]) => post(...args) as unknown,
      GET: (...args: unknown[]) => get(...args) as unknown,
      PUT: (...args: unknown[]) => put(...args) as unknown,
    },
  }
})

afterEach(() => {
  cleanup()
  post.mockReset()
  get.mockReset()
  put.mockReset()
})

const ok = (data: unknown) => Promise.resolve({ data, error: undefined, response: { status: 200 } })

type Group = Record<string, unknown>
const group = (key: string, depth: number, extra: Group = {}): Group => ({
  definitionId: `${key}:1:old`,
  definitionKey: key,
  version: 1,
  instanceCount: 1,
  minDepth: depth,
  maxDepth: depth,
  status: 'PENDING',
  executableCount: 0,
  blockerCount: 0,
  ...extra,
})

const plan = (overrides: Record<string, unknown> = {}, groups?: Group[]) => ({
  id: 'plan-1',
  engineId: 'engine-a',
  rootInstanceIds: ['root'],
  nodes: [
    { instanceId: 'root', definitionId: 'demoRoot:1:old', depth: 0, businessKey: 'ORDER-1' },
    { instanceId: 'a1', definitionId: 'demoA:1:old', depth: 1, parentInstanceId: 'root' },
    { instanceId: 'b1', definitionId: 'demoB:1:old', depth: 2, parentInstanceId: 'a1' },
  ],
  groups: groups ?? [group('demoRoot', 0), group('demoA', 1), group('demoB', 2)],
  truncations: [],
  circularReferences: [],
  executable: false,
  executableCount: 0,
  notSentCount: 0,
  notExecutableReason: 'Still to decide: demoRoot v1, demoA v1, demoB v1.',
  banner: 'Inspector estimate — the engine is the only ground truth at execute',
  ...overrides,
})

const READY = plan(
  { executable: true, executableCount: 3, notExecutableReason: undefined },
  ['demoRoot', 'demoA', 'demoB'].map((key, depth) =>
    group(key, depth, {
      status: 'CONFIGURED',
      targetDefinitionId: `${key}:2:new`,
      executableCount: 1,
    }),
  ),
)

interface Setup {
  environment?: string
  created?: unknown
  /** What GET /plans/{id} answers (the poll while a group is ANALYZING). */
  polled?: unknown
  decided?: unknown
}

function renderModal({ environment = 'dev', created = plan(), polled, decided }: Setup = {}) {
  get.mockImplementation((path: string, init?: { params?: { path?: { key?: string } } }) => {
    if (path === '/api/engines') return ok([{ id: 'engine-a', name: 'A', environment }])
    if (path === '/api/migration/hierarchy/plans/{id}') return ok(polled ?? created)
    const key = init?.params?.path?.key ?? '?'
    return ok({
      key,
      versions: [
        { definitionId: `${key}:1:old`, version: 1 },
        { definitionId: `${key}:2:new`, version: 2, latest: true },
      ],
    })
  })
  post.mockImplementation((path: string) => {
    if (path === '/api/migration/hierarchy/plans') return ok(created)
    return ok({ id: 'job-1', totalItems: 3 })
  })
  put.mockImplementation(() => ok(decided ?? created))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <OpsDrawerProvider>
          <HierarchyMigrateModal engineId="engine-a" rootInstanceId="root" onClose={vi.fn()} />
        </OpsDrawerProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const groupOf = (name: string) => screen.findByRole('group', { name })
const callTo = (mock: typeof post, path: string) => mock.mock.calls.find(([p]) => p === path)

describe('HierarchyMigrateModal (#497)', () => {
  it('stores a plan for the root and shows the tree with every definition Pending', async () => {
    renderModal()

    const tree = await screen.findByRole('list', { name: 'Plan tree' })
    expect(callTo(post, '/api/migration/hierarchy/plans')?.[1]).toEqual({
      params: { query: { engineId: 'engine-a' } },
      body: { rootInstanceId: 'root' },
    })
    const links = within(tree).getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual(['root', 'a1', 'b1'])
    // b1 is nested under a1, a1 under root: the tree, not a flat list.
    const parentItem = (link: HTMLElement) => link.closest('li')?.parentElement?.closest('li')
    expect(parentItem(links[2])).toBe(links[1].closest('li'))
    expect(parentItem(links[1])).toBe(links[0].closest('li'))

    for (const name of ['demoRoot v1', 'demoA v1', 'demoB v1']) {
      expect(within(await groupOf(name)).getByText('Pending')).toBeTruthy()
    }
    expect(screen.getByText('Still to decide: demoRoot v1, demoA v1, demoB v1.')).toBeTruthy()
    const migrate = screen.getByRole('button', { name: /^Migrate/ })
    expect((migrate as HTMLButtonElement).disabled).toBe(true)
  })

  it('Configure sends the definition, the chosen target and the mapping; the row turns Configured', async () => {
    const decided = plan({}, [
      group('demoRoot', 0),
      group('demoA', 1),
      group('demoB', 2, {
        status: 'CONFIGURED',
        targetDefinitionId: 'demoB:2:new',
        executableCount: 1,
        blockerCount: 0,
      }),
    ])
    renderModal({ decided })
    const row = await groupOf('demoB v1')
    await within(row).findByRole('option', { name: /v2/ })
    fireEvent.change(within(row).getByLabelText('Mapping JSON for demoB v1'), {
      target: { value: '[{"fromActivityId":"oldTask","toActivityId":"newTask"}]' },
    })
    fireEvent.click(within(row).getByRole('button', { name: 'Configure' }))

    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/migration/hierarchy/plans/{id}/groups', {
        params: { path: { id: 'plan-1' } },
        body: {
          definitionId: 'demoB:1:old',
          toDefinitionId: 'demoB:2:new',
          mappings: [{ fromActivityId: 'oldTask', toActivityId: 'newTask' }],
        },
      })
    })
    const configured = await groupOf('demoB v1')
    await within(configured).findByText('Configured')
    expect(within(configured).getByText(/1 will be sent · 0 not sent/)).toBeTruthy()
  })

  it('Skip sends skip for that definition only; the row turns Skipped', async () => {
    const decided = plan({}, [
      group('demoRoot', 0),
      group('demoA', 1, { status: 'SKIPPED' }),
      group('demoB', 2),
    ])
    renderModal({ decided })
    fireEvent.click(within(await groupOf('demoA v1')).getByRole('button', { name: 'Skip' }))

    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/migration/hierarchy/plans/{id}/groups', {
        params: { path: { id: 'plan-1' } },
        body: { definitionId: 'demoA:1:old', skip: true },
      })
    })
    await within(await groupOf('demoA v1')).findByText('Skipped')
    expect(within(await groupOf('demoB v1')).getByText('Pending')).toBeTruthy()
  })

  it('a mapping that is not a JSON array disables Configure and says why', async () => {
    renderModal()
    const row = await groupOf('demoB v1')
    await within(row).findByRole('option', { name: /v2/ })
    const configure = within(row).getByRole('button', { name: 'Configure' })
    expect((configure as HTMLButtonElement).disabled).toBe(false)

    fireEvent.change(within(row).getByLabelText('Mapping JSON for demoB v1'), {
      target: { value: '{"fromActivityId":"a"}' },
    })
    expect((configure as HTMLButtonElement).disabled).toBe(true)
    expect(within(row).getByText('Mapping JSON must be an array of mappings.')).toBeTruthy()
    expect(put).not.toHaveBeenCalled()
  })

  it('polls the stored plan while a definition is Analyzing', async () => {
    const analyzing = plan({}, [
      group('demoRoot', 0),
      group('demoA', 1),
      group('demoB', 2, { status: 'ANALYZING' }),
    ])
    const settled = plan({}, [
      group('demoRoot', 0),
      group('demoA', 1),
      group('demoB', 2, { status: 'ANALYSIS_FAILED' }),
    ])
    renderModal({ created: analyzing, polled: settled })
    await within(await groupOf('demoB v1')).findByText('Analyzing…')

    await waitFor(
      () => {
        expect(get).toHaveBeenCalledWith('/api/migration/hierarchy/plans/{id}', {
          params: { path: { id: 'plan-1' } },
        })
      },
      { timeout: 3000 },
    )
    await within(await groupOf('demoB v1')).findByText('Analysis failed')
  })

  it('submits the plan id only, with the reason', async () => {
    renderModal({ created: READY })
    await groupOf('demoB v1')
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'move the tree off the bad deploy' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Migrate 3' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/bulk/migrate-hierarchy', {
        body: {
          planId: 'plan-1',
          reason: 'move the tree off the bad deploy',
          ticketId: undefined,
          confirmedCount: undefined,
        },
      })
    })
  })

  it('on prod, the executable count must be typed before Migrate enables', async () => {
    renderModal({ environment: 'prod', created: READY })
    await groupOf('demoB v1')
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'move the tree off the bad deploy' },
    })
    const migrate = screen.getByRole('button', { name: 'Migrate 3' })
    expect((migrate as HTMLButtonElement).disabled).toBe(true)

    fireEvent.change(await screen.findByLabelText(/Type the/), { target: { value: '3' } })
    expect((migrate as HTMLButtonElement).disabled).toBe(false)
    fireEvent.click(migrate)

    await waitFor(() => {
      expect(callTo(post, '/api/bulk/migrate-hierarchy')?.[1]).toEqual({
        body: {
          planId: 'plan-1',
          reason: 'move the tree off the bad deploy',
          ticketId: undefined,
          confirmedCount: 3,
        },
      })
    })
  })

  it('a truncated plan says where the walk stopped, lists circular references, and cannot be submitted', async () => {
    renderModal({
      created: plan({
        truncations: [{ instanceId: 'a1', kind: 'breadth', enumerated: 50, total: 10000 }],
        circularReferences: [
          { definitionKey: 'demoA', instanceId: 'b1', ancestorInstanceId: 'a1', depth: 2 },
        ],
        notExecutableReason: 'The plan does not hold the whole tree (breadth at a1).',
      }),
    })
    await groupOf('demoB v1')

    expect(screen.getByText(/engine-a:a1 \(breadth\): 50 of 10000 read/)).toBeTruthy()
    expect(screen.getByText(/demoA: engine-a:b1 runs under engine-a:a1/)).toBeTruthy()
    expect(screen.getByText('The plan does not hold the whole tree (breadth at a1).')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'move the tree off the bad deploy' },
    })
    const migrate = screen.getByRole('button', { name: /^Migrate/ })
    expect((migrate as HTMLButtonElement).disabled).toBe(true)
  })
})

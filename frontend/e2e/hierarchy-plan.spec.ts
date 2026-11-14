// #497 smoke: the hierarchy plan builder in a real browser — open it from the Hierarchy tab,
// see the tree with every definition Pending, configure two definitions and skip one, submit.
// Hermetic (every /api call fulfilled below); the BFF stand-in keeps the plan's group status the
// way the real plan API reports it, so the screen is proven to show what it is told.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const KEYS = ['demoHierarchyRoot', 'demoHierarchyA', 'demoHierarchyB']
const NODES = [
  { instanceId: 'root-1', definitionId: 'demoHierarchyRoot:1:r1', depth: 0, businessKey: 'H-1' },
  { instanceId: 'a-1', definitionId: 'demoHierarchyA:1:a1', depth: 1, parentInstanceId: 'root-1' },
  { instanceId: 'b-1', definitionId: 'demoHierarchyB:1:b1', depth: 2, parentInstanceId: 'a-1' },
]

interface Decision {
  definitionId?: string
  toDefinitionId?: string
  skip?: boolean
  mappings?: unknown[]
}

/** The plan as the BFF would report it after the given decisions. */
function planView(decisions: Decision[]) {
  const groups = NODES.map((node, depth) => {
    const decision = [...decisions].reverse().find((d) => d.definitionId === node.definitionId)
    const configured = decision !== undefined && decision.skip !== true
    return {
      definitionId: node.definitionId,
      definitionKey: KEYS[depth],
      version: 1,
      instanceCount: 1,
      minDepth: depth,
      maxDepth: depth,
      status: decision === undefined ? 'PENDING' : configured ? 'CONFIGURED' : 'SKIPPED',
      targetDefinitionId: configured ? decision.toDefinitionId : undefined,
      executableCount: configured ? 1 : 0,
      blockerCount: 0,
    }
  })
  const pending = groups.filter((g) => g.status === 'PENDING')
  const executableCount = groups.reduce((sum, g) => sum + g.executableCount, 0)
  return {
    id: '7d3c6a0e-0000-4000-8000-000000000497',
    engineId: 'eng1',
    rootInstanceIds: ['root-1'],
    nodes: NODES,
    groups,
    truncations: [],
    circularReferences: [],
    executable: pending.length === 0 && executableCount > 0,
    executableCount,
    notSentCount: 0,
    notExecutableReason:
      pending.length === 0
        ? undefined
        : `Still to decide: ${pending.map((g) => `${g.definitionKey} v1`).join(', ')}.`,
    banner: 'Inspector estimate — the engine is the only ground truth at execute',
  }
}

const JOB = {
  id: 'job-h',
  verb: 'migrate-instance',
  state: 'RUNNING',
  submittedBy: 'admin',
  submittedAt: '2026-11-14T12:00:00Z',
  reason: 'INC-497: move the tree off the bad deploy',
  scopeLabel: 'hierarchy migrate · plan 7d3c6a0e',
  totalItems: 2,
  tallies: {},
  items: [],
}

// Predicate, never the '**/api/**' glob (TEST-STRATEGY §9).
async function mockBff(page: Page, decisions: Decision[], submitted: unknown[]) {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (pathname === '/api/migration/hierarchy/plans' && method === 'POST') {
        await route.fulfill({ json: planView(decisions) })
      } else if (pathname.endsWith('/groups') && method === 'PUT') {
        decisions.push(route.request().postDataJSON() as Decision)
        await route.fulfill({ json: planView(decisions) })
      } else if (pathname.startsWith('/api/migration/hierarchy/plans/')) {
        await route.fulfill({ json: planView(decisions) })
      } else if (pathname === '/api/bulk/migrate-hierarchy') {
        submitted.push(route.request().postDataJSON())
        await route.fulfill({ json: JOB })
      } else if (pathname === '/api/bulk/events') {
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: 'retry: 1000\n\n',
        })
      } else if (pathname === '/api/bulk/job-h') {
        await route.fulfill({ json: JOB })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: submitted.length > 0 ? [JOB] : [] })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'ADMIN', engineRoles: { eng1: 'ADMIN' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({
          json: [
            {
              id: 'eng1',
              name: 'Orders DEV',
              environment: 'dev',
              mode: 'FULL',
              reachable: true,
              capabilities: { changeState: true, migration: true },
            },
          ],
        })
      } else if (pathname.startsWith('/api/definitions/eng1/') && pathname.endsWith('/versions')) {
        const key = pathname.split('/')[4]
        const old = NODES.find((node) => node.definitionId.startsWith(`${key}:`))?.definitionId
        await route.fulfill({
          json: {
            engineId: 'eng1',
            key,
            complete: true,
            versions: [
              { definitionId: `${key}:2:new`, version: 2, latest: true, runningInstanceCount: 0 },
              { definitionId: old, version: 1, latest: false, runningInstanceCount: 1 },
            ],
          },
        })
      } else if (pathname === '/api/instances/eng1/root-1/hierarchy') {
        await route.fulfill({
          json: {
            maxDepth: 10,
            breadthCap: 50,
            root: {
              processInstanceId: 'root-1',
              requested: true,
              definitionKey: 'demoHierarchyRoot',
              definitionVersion: 1,
              startTime: '2026-11-14T08:00:00Z',
              childTotal: 1,
              children: [
                {
                  processInstanceId: 'a-1',
                  definitionKey: 'demoHierarchyA',
                  definitionVersion: 1,
                  startTime: '2026-11-14T08:00:01Z',
                  childTotal: 0,
                  children: [],
                },
              ],
            },
          },
        })
      } else if (pathname.endsWith('/diagram')) {
        await route.fulfill({
          json: { xml: '<x/>', activeActivityIds: [], deadLetterActivityIds: [] },
        })
      } else if (pathname.endsWith('/variables')) {
        await route.fulfill({
          json: { processVariables: [], executionScopes: [], source: 'runtime' },
        })
      } else if (pathname === '/api/instances/eng1/root-1') {
        await route.fulfill({
          json: {
            engineId: 'eng1',
            processInstanceId: 'root-1',
            compositeId: 'eng1:root-1',
            businessKey: 'H-1',
            status: 'ACTIVE',
            flags: { ended: false, suspended: false },
            definitionKey: 'demoHierarchyRoot',
            definitionVersion: 1,
            processDefinitionId: 'demoHierarchyRoot:1:r1',
            startTime: '2026-11-14T08:00:00Z',
            currentActivities: [],
            waitingFor: [],
          },
        })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

test('plan a hierarchy migration from the Hierarchy tab: configure, skip, submit the plan id', async ({
  page,
}) => {
  const decisions: Decision[] = []
  const submitted: unknown[] = []
  await mockBff(page, decisions, submitted)
  await page.goto('/inspect/eng1/root-1?tab=hierarchy')

  await page.getByRole('button', { name: 'Migrate this hierarchy…' }).click()
  const modal = page.getByRole('dialog')
  const tree = modal.getByRole('list', { name: 'Plan tree' })
  await expect(tree.getByRole('link')).toHaveText(['root-1', 'a-1', 'b-1'])

  const rootGroup = modal.getByRole('group', { name: 'demoHierarchyRoot v1' })
  const aGroup = modal.getByRole('group', { name: 'demoHierarchyA v1' })
  const bGroup = modal.getByRole('group', { name: 'demoHierarchyB v1' })
  for (const group of [rootGroup, aGroup, bGroup]) await expect(group).toContainText('Pending')
  const migrate = modal.getByRole('button', { name: /^Migrate/ })
  await expect(migrate).toBeDisabled()
  await expect(modal).toContainText('Still to decide: demoHierarchyRoot v1')
  await scanA11y(page, 'hierarchy plan builder, every definition pending')

  await bGroup.getByRole('button', { name: 'Skip' }).click()
  await expect(bGroup).toContainText('Skipped')
  await aGroup
    .getByLabel('Mapping JSON for demoHierarchyA v1')
    .fill('[{"fromActivityId":"oldTask","toActivityId":"newTask"}]')
  await aGroup.getByRole('button', { name: 'Configure' }).click()
  await expect(aGroup).toContainText('Configured')
  await expect(aGroup).toContainText('→ v2 · 1 will be sent · 0 not sent')
  await expect(migrate).toBeDisabled()
  await rootGroup.getByRole('button', { name: 'Configure' }).click()
  await expect(rootGroup).toContainText('Configured')

  await modal.getByLabel(/Reason/).fill('INC-497: move the tree off the bad deploy')
  await expect(modal.getByRole('button', { name: 'Migrate 2' })).toBeEnabled()
  await scanA11y(page, 'hierarchy plan builder, ready to submit')
  await modal.getByRole('button', { name: 'Migrate 2' }).click()

  await expect(modal).toHaveCount(0)
  expect(decisions).toEqual([
    { definitionId: 'demoHierarchyB:1:b1', skip: true },
    {
      definitionId: 'demoHierarchyA:1:a1',
      toDefinitionId: 'demoHierarchyA:2:new',
      mappings: [{ fromActivityId: 'oldTask', toActivityId: 'newTask' }],
    },
    {
      definitionId: 'demoHierarchyRoot:1:r1',
      toDefinitionId: 'demoHierarchyRoot:2:new',
      mappings: [],
    },
  ])
  expect(submitted).toEqual([
    {
      planId: '7d3c6a0e-0000-4000-8000-000000000497',
      reason: 'INC-497: move the tree off the bad deploy',
    },
  ])
  await expect(page.getByRole('complementary', { name: 'Operations drawer' })).toBeVisible()
})

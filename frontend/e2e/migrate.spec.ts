// v2 instance-migration smokes: the capability-gated greying, and the pre-check-first arc
// (version pick → "Check mapping" → the BFF STATIC estimate with a targeted from→to dropdown
// for the flagged activity → re-check → execute unlocks). Hermetic — every /api call is
// fulfilled below; the invariant these protect is pre-check-first: the migrate/execute route
// is NEVER hit, and the migrate button exists only after a CLEAN pre-check.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const CAPABLE_ENGINE = {
  id: 'eng1',
  name: 'Orders DEV',
  environment: 'dev',
  mode: 'FULL',
  reachable: true,
  capabilities: { changeState: true, migration: true },
}

const INCAPABLE_ENGINE = {
  ...CAPABLE_ENGINE,
  capabilities: { changeState: true, migration: false },
}

const RUNNING_VITALS = {
  engineId: 'eng1',
  processInstanceId: 'inst-1',
  compositeId: 'eng1:inst-1',
  businessKey: 'order-4711',
  status: 'ACTIVE',
  flags: { ended: false, suspended: false },
  definitionKey: 'orderFlow',
  definitionName: 'Order flow',
  definitionVersion: 3,
  processDefinitionId: 'orderFlow:3:abc',
  startTime: '2026-07-01T08:00:00Z',
  currentActivities: [
    { activityId: 'reviewTask', activityName: 'Review order', activityType: 'userTask' },
  ],
  waitingFor: [],
}

const VERSIONS = {
  engineId: 'eng1',
  key: 'orderFlow',
  latestVersion: 5,
  totalVersions: 3,
  complete: true,
  versions: [
    {
      definitionId: 'orderFlow:5:e5',
      version: 5,
      name: 'Order flow',
      latest: true,
      runningInstanceCount: 12,
    },
    {
      definitionId: 'orderFlow:4:d4',
      version: 4,
      name: 'Order flow',
      latest: false,
      runningInstanceCount: 3,
    },
    {
      definitionId: 'orderFlow:3:abc',
      version: 3,
      name: 'Order flow',
      latest: false,
      runningInstanceCount: 37,
    },
  ],
}

const BANNER =
  'Inspector pre-check — this is not a Flowable validation. The engine checks this only when you execute.'

const FLAGGED_PREVIEW = {
  engineId: 'eng1',
  instanceId: 'inst-1',
  fromDefinitionId: 'orderFlow:3:abc',
  fromDefinitionKey: 'orderFlow',
  fromVersion: 3,
  toProcessDefinitionId: 'orderFlow:5:e5',
  toVersion: 5,
  engineValidated: false,
  executable: false,
  activities: [
    {
      fromActivityId: 'reviewTask',
      fromName: 'Review order',
      fromType: 'userTask',
      status: 'FLAGGED_UNMAPPED',
      blocker: true,
      warning: false,
      detail: "No activity with id 'reviewTask' exists in the target version.",
    },
  ],
  targetActivities: [{ id: 'approveTask', name: 'Approve order', type: 'userTask' }],
  activityStateDigest: 'digest-abc',
  callActivityChildCount: 0,
  method: 'POST',
  enginePath: '/runtime/process-instances/inst-1/migrate',
  restBody: { toProcessDefinitionId: 'orderFlow:5:e5', activityMappings: [] },
  summary: "1 active activity can't be auto-mapped — pick a target.",
  banner: BANNER,
}

const MAPPED_PREVIEW = {
  ...FLAGGED_PREVIEW,
  executable: true,
  activities: [
    {
      fromActivityId: 'reviewTask',
      fromName: 'Review order',
      fromType: 'userTask',
      status: 'MAPPED_BY_OVERRIDE',
      blocker: false,
      warning: false,
      toActivityId: 'approveTask',
      toType: 'userTask',
      detail: "Mapped by the operator to 'approveTask'.",
    },
  ],
  restBody: {
    toProcessDefinitionId: 'orderFlow:5:e5',
    activityMappings: [{ fromActivityId: 'reviewTask', toActivityId: 'approveTask' }],
  },
  summary: 'Migrate this instance from v3 to v5. All 1 active activity map.',
}

/** Fulfills every /api call; records mutating migrate calls so tests assert none fired. */
async function mockBff(page: Page, engine: object, executed: string[]): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && pathname.endsWith('/migrate/execute')) {
        executed.push(pathname)
        await route.fulfill({ status: 500, json: { title: 'must never be called' } })
        return
      }
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'ADMIN', engineRoles: { eng1: 'ADMIN' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [engine] })
      } else if (pathname.endsWith('/migrate/preview')) {
        // Flagged until the operator supplies a mapping; clean once mappings are present.
        const body = route.request().postDataJSON() as { mappings?: unknown[] }
        const mapped = Array.isArray(body.mappings) && body.mappings.length > 0
        await route.fulfill({ json: mapped ? MAPPED_PREVIEW : FLAGGED_PREVIEW })
      } else if (pathname === '/api/definitions/eng1/orderFlow/versions') {
        await route.fulfill({ json: VERSIONS })
      } else if (pathname.endsWith('/diagram')) {
        await route.fulfill({
          json: { xml: '<x/>', activeActivityIds: [], deadLetterActivityIds: [] },
        })
      } else if (pathname.endsWith('/variables')) {
        await route.fulfill({
          json: { processVariables: [], executionScopes: [], source: 'runtime' },
        })
      } else if (pathname === '/api/instances/eng1/inst-1') {
        await route.fulfill({ json: RUNNING_VITALS })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

test('migrate is greyed when the engine lacks the migration capability', async ({ page }) => {
  const executed: string[] = []
  await mockBff(page, INCAPABLE_ENGINE, executed)
  await page.goto('/inspect/eng1/inst-1')

  const migrate = page.getByRole('button', { name: 'Migrate', exact: true })
  await expect(migrate).toBeDisabled()
  await expect(migrate).toHaveAttribute('title', /capability/)
  expect(executed).toEqual([])
})

test('migrate is pre-check-first: pick version, map the flagged activity, execute unlocks — execute never fires', async ({
  page,
}) => {
  const executed: string[] = []
  await mockBff(page, CAPABLE_ENGINE, executed)
  await page.goto('/inspect/eng1/inst-1')

  const open = page.getByRole('button', { name: 'Migrate', exact: true })
  await expect(open).toBeEnabled()
  await open.click()

  // Step 1 (pick version): default is the latest (v5); no migrate affordance yet.
  const pick = page.getByRole('dialog', { name: /Migrate — move this case/ })
  await expect(pick).toBeVisible()
  await expect(pick.getByRole('combobox', { name: 'target version' })).toHaveValue('orderFlow:5:e5')
  await expect(page.getByRole('button', { name: /^Migrate order-4711/ })).toHaveCount(0)
  await pick.getByRole('button', { name: 'Check mapping →' }).click()

  // Step 2 (pre-check): the estimate, honestly labelled, with the flagged activity + dropdown.
  const check = page.getByRole('dialog', { name: 'Migrate — check the mapping' })
  await expect(check).toBeVisible()
  await expect(
    check.getByRole('alert').filter({ hasText: 'not a Flowable validation' }),
  ).toBeVisible()
  await expect(check.getByText('can’t be auto-mapped')).toBeVisible()
  // No migrate button while an activity is unmapped — only Re-check.
  await expect(check.getByRole('button', { name: /^Migrate order-4711/ })).toHaveCount(0)
  await expect(check.getByRole('button', { name: 'Re-check mapping' })).toBeVisible()

  // Map the flagged activity, then re-check → the pre-check comes back clean.
  await check.getByRole('combobox', { name: 'target for reviewTask' }).selectOption('approveTask')
  await check.getByRole('button', { name: 'Re-check mapping' }).click()

  // Now executable: the migrate button appears; reason (≥10) unlocks it (dev — no typed token).
  const migrate = check.getByRole('button', { name: 'Migrate order-4711 to v5' })
  await expect(migrate).toBeVisible()
  await expect(migrate).toBeDisabled()
  await check.getByRole('textbox').fill('INC-9000: move cohort off the bad deploy')
  await expect(migrate).toBeEnabled()

  // Cancel out — the whole arc must leave the engine untouched.
  await check.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(check).toHaveCount(0)
  expect(executed).toEqual([])
})

// v2 instance-migration smokes: the capability-gated greying, and the pre-check-first arc
// (version pick → "Check mapping" → the BFF STATIC estimate with a targeted from→to dropdown
// for the flagged activity → re-check → execute unlocks). Hermetic — every /api call is
// fulfilled below; the invariant these protect is pre-check-first: the migrate/execute route
// is NEVER hit, and the migrate button exists only after a CLEAN pre-check.
import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

// The seeded v1→v2 fixture (reviewTask renamed approveTask) — the same files the migration
// ITs deploy, served as the two definitions' XML for the dual-canvas editor (#498).
const FIXTURE_XML = {
  v1: readFileSync(
    new URL('../../docker/processes/demo-migration-v1.bpmn20.xml', import.meta.url),
    'utf8',
  ),
  v2: readFileSync(
    new URL('../../docker/processes/demo-migration-v2.bpmn20.xml', import.meta.url),
    'utf8',
  ),
}

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
      // The typed finding the BFF now carries (INSTANCE-MIGRATION.md §14.3) — worded so it can
      // never be read as an engine verdict.
      findings: [
        {
          code: 'UNMAPPED_ACTIVE_ACTIVITY',
          severity: 'BLOCKER_ADVICE',
          activityId: 'reviewTask',
          detail:
            'The Inspector cannot build a migration instruction for this activity — there is nothing to send.',
        },
      ],
    },
  ],
  findings: [],
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

// Two flagged sources (both carry the blocker finding, as the BFF always attaches one to a
// FLAGGED_UNMAPPED activity) plus a warning on a third activity — the selection must stay
// visible ON a blocker-marked source, and the warning gets its own glyph (#498 review).
const TWO_FLAGGED_PREVIEW = {
  ...FLAGGED_PREVIEW,
  activities: [
    ...FLAGGED_PREVIEW.activities,
    {
      fromActivityId: 'start',
      fromType: 'startEvent',
      status: 'FLAGGED_UNMAPPED',
      blocker: true,
      warning: false,
      detail: "No activity with id 'start' exists in the target version.",
      findings: [
        {
          code: 'UNMAPPED_ACTIVE_ACTIVITY',
          severity: 'BLOCKER_ADVICE',
          activityId: 'start',
          detail: 'The Inspector cannot build a migration instruction for this activity.',
        },
      ],
    },
  ],
  findings: [
    {
      code: 'ACTIVE_SCOPE_REMOVED',
      severity: 'WARNING',
      activityId: 'end',
      detail: 'Scope-local state of the removed scope has no target scope.',
    },
  ],
}

/** Fulfills every /api call; records mutating migrate calls so tests assert none fired. */
async function mockBff(
  page: Page,
  engine: object,
  executed: string[],
  flagged: object = FLAGGED_PREVIEW,
): Promise<void> {
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
        await route.fulfill({ json: mapped ? MAPPED_PREVIEW : flagged })
      } else if (pathname.startsWith('/api/definitions/eng1/') && pathname.endsWith('/xml')) {
        const current = decodeURIComponent(pathname).includes('orderFlow:3:abc')
        await route.fulfill({ json: { xml: current ? FIXTURE_XML.v1 : FIXTURE_XML.v2 } })
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
  await scanA11y(page, 'migrate button disabled, capability gated')
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
  await scanA11y(page, 'migrate version-pick dialog open')
  await pick.getByRole('button', { name: 'Check mapping →' }).click()

  // Step 2 (pre-check): the estimate, honestly labelled, with the flagged activity + dropdown.
  const check = page.getByRole('dialog', { name: 'Migrate — check the mapping' })
  await expect(check).toBeVisible()
  await expect(
    check.getByRole('alert').filter({ hasText: 'not a Flowable validation' }),
  ).toBeVisible()
  await expect(check.getByText('can’t be auto-mapped')).toBeVisible()
  // The typed BLOCKER_ADVICE finding (§14.3): "we cannot build the instruction", not a verdict.
  await expect(check.getByText('Blocked before the engine (1)')).toBeVisible()
  await expect(check.getByText('there is nothing to send', { exact: false })).toBeVisible()
  await expect(
    check.getByText('BFF estimate — the engine is the only ground truth at execute.'),
  ).toBeVisible()
  // No migrate button while an activity is unmapped — only Re-check.
  await expect(check.getByRole('button', { name: /^Migrate order-4711/ })).toHaveCount(0)
  await expect(check.getByRole('button', { name: 'Re-check mapping' })).toBeVisible()
  await scanA11y(page, 'migrate pre-check dialog, flagged activity unmapped')

  // Map the flagged activity, then re-check → the pre-check comes back clean.
  await check.getByRole('combobox', { name: 'target for reviewTask' }).selectOption('approveTask')
  await check.getByRole('button', { name: 'Re-check mapping' }).click()

  // Now executable: the migrate button appears; reason (≥10) unlocks it (dev — no typed token).
  const migrate = check.getByRole('button', { name: 'Migrate order-4711 to v5' })
  await expect(migrate).toBeVisible()
  await expect(migrate).toBeDisabled()
  await check.getByRole('textbox').fill('INC-9000: move cohort off the bad deploy')
  await expect(migrate).toBeEnabled()
  await scanA11y(page, 'migrate pre-check dialog, mapping resolved and reason filled')

  // Cancel out — the whole arc must leave the engine untouched.
  await check.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(check).toHaveCount(0)
  expect(executed).toEqual([])
})

test('mapping editor: click-to-map on the two diagrams feeds the pair list (#498)', async ({
  page,
}) => {
  const executed: string[] = []
  const previews: unknown[] = []
  page.on('request', (request) => {
    if (request.url().endsWith('/migrate/preview')) previews.push(request.postDataJSON())
  })
  await mockBff(page, CAPABLE_ENGINE, executed)
  await page.goto('/inspect/eng1/inst-1')
  await page.getByRole('button', { name: 'Migrate', exact: true }).click()
  await page.getByRole('button', { name: 'Check mapping →' }).click()

  const check = page.getByRole('dialog', { name: 'Migrate — check the mapping' })
  await check.getByRole('button', { name: 'Diagrams' }).click()
  const source = check.getByRole('region', { name: 'Source diagram (current version)' })
  const target = check.getByRole('region', { name: 'Target diagram (new version)' })

  // The pre-check blocker is overlaid on the source canvas with a glyph + label, not hue.
  await expect(
    source.getByRole('img', { name: 'pre-check blocker on this activity' }),
  ).toBeVisible()

  // data-element-id is diagram-js's own registry attribute — a stable click target.
  await source.locator('[data-element-id="reviewTask"]').click()
  await expect(check.getByRole('combobox', { name: 'pair source' })).toHaveValue('reviewTask')
  await target.locator('[data-element-id="approveTask"]').click()
  await expect(check.getByRole('combobox', { name: 'pair target' })).toHaveValue('approveTask')
  await check.getByRole('button', { name: 'Add to map' }).click()

  await expect(
    check.getByRole('button', { name: 'Remove reviewTask to approveTask' }),
  ).toBeVisible()
  await expect(target.getByRole('img', { name: 'mapped' })).toBeVisible()
  await scanA11y(page, 'migrate mapping editor, diagrams mode with one pair')

  // The re-check proposes exactly the pair the canvases produced; the BFF rebuilds the
  // wire document from it (the "exact engine request" block shows what it would dispatch).
  await check.getByRole('button', { name: 'Re-check mapping' }).click()
  await expect(check.getByRole('button', { name: 'Migrate order-4711 to v5' })).toBeVisible()
  expect(previews.at(-1)).toMatchObject({
    toDefinitionId: 'orderFlow:5:e5',
    mappings: [{ fromActivityId: 'reviewTask', toActivityId: 'approveTask' }],
  })
  expect(executed).toEqual([])
})

test('mapping editor: the selected source stays visible on a blocker-marked activity (#498)', async ({
  page,
}) => {
  await mockBff(page, CAPABLE_ENGINE, [], TWO_FLAGGED_PREVIEW)
  await page.goto('/inspect/eng1/inst-1')
  await page.getByRole('button', { name: 'Migrate', exact: true }).click()
  await page.getByRole('button', { name: 'Check mapping →' }).click()
  const check = page.getByRole('dialog', { name: 'Migrate — check the mapping' })
  await check.getByRole('button', { name: 'Diagrams' }).click()
  const source = check.getByRole('region', { name: 'Source diagram (current version)' })

  await expect(source.getByRole('img', { name: 'pre-check blocker on this activity' })).toHaveCount(
    2,
  )
  await expect(
    source.getByRole('img', { name: 'pre-check warning on this activity' }),
  ).toBeVisible()

  // The painted outline of an element: stroke colour + width + fill, as computed.
  const look = (id: string) =>
    source
      .locator(`.djs-element[data-element-id="${id}"] .djs-visual > :first-child`)
      .evaluate((node) => {
        const style = getComputedStyle(node)
        return `${style.stroke} ${style.strokeWidth} ${style.fill}`
      })

  const unselected = await look('reviewTask')
  await source.locator('[data-element-id="start"]').click()
  await expect(check.getByRole('combobox', { name: 'pair source' })).toHaveValue('start')
  await expect.poll(() => look('start')).not.toBe(unselected)
  const selected = await look('start')

  // Switching the select moves the visible selection: the two flagged sources swap looks.
  await check.getByRole('combobox', { name: 'pair source' }).selectOption('reviewTask')
  await expect.poll(() => look('reviewTask')).toBe(selected)
  expect(await look('start')).toBe(unselected)

  // Adding a pair re-marks the source canvas; the operator's zoom survives it.
  // The viewport matrix, rounded (diagram-js rewrites the attribute in a shorter form).
  const viewport = async () =>
    ((await source.locator('svg .viewport').first().getAttribute('transform')) ?? '')
      .split(/[^-\d.]+/)
      .filter((part) => part !== '')
      .map((part) => Number(part).toFixed(2))
      .join(' ')
  const before = await viewport()
  await source.locator('[data-element-id="end"]').hover()
  await page.keyboard.down('Control')
  await page.mouse.wheel(0, -300)
  await page.keyboard.up('Control')
  await expect.poll(viewport).not.toBe(before)
  const zoomed = await viewport()
  await check.getByRole('combobox', { name: 'pair target' }).selectOption('approveTask')
  await check.getByRole('button', { name: 'Add to map' }).click()
  await expect(source.getByRole('img', { name: 'mapped' })).toBeVisible()
  expect(await viewport()).toBe(zoomed)
})

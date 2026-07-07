// v1.x #1 smokes: the triage "Retry group" arc — card button → Tier-3 modal → dispatch →
// operations-drawer handoff. Hermetic (every /api call fulfilled below). The invariant
// these tests exist to protect is SERVER-SIDE RESOLUTION: the submit carries the group's
// coordinates only — no instance ID ever crosses the wire from the browser.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const HASH = 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2'

function engine(environment: string) {
  return {
    id: 'eng1',
    name: environment === 'prod' ? 'Payments PROD' : 'Payments DEV',
    environment,
    mode: 'FULL',
    reachable: true,
  }
}

const GROUP = {
  signatureHash: HASH,
  algoVersion: 1,
  exceptionClass: 'java.lang.ArithmeticException',
  normalizedMessage: '/ by zero',
  sampleRawMessage: 'java.lang.ArithmeticException: / by zero',
  total: 3,
  deadLetterCount: 2,
  retryingCount: 1,
  countsByEngine: { eng1: { 'payment:v3': 3 } },
}

const JOB = {
  id: 'job-9',
  verb: 'retry-job',
  state: 'RUNNING',
  submittedBy: 'responder',
  submittedAt: '2026-07-07T03:33:00Z',
  reason: 'INC-4711: upstream data fixed, drain the class',
  totalItems: 2,
  tallies: { pending: 2 },
}

const JOB_DETAIL = {
  ...JOB,
  items: [
    { ordinal: 0, engineId: 'eng1', instanceId: 'p-1', state: 'pending', detail: null },
    { ordinal: 1, engineId: 'eng1', instanceId: 'p-2', state: 'pending', detail: null },
  ],
}

interface MockState {
  /** Bodies of every POST /api/bulk/error-class — the wire-contract evidence. */
  submits: unknown[]
}

/** Fulfills every /api call. Predicate (never the '**​/api/**' glob): the glob would also
 *  hijack Vite's /src/api/* module requests and brick the dev server (TEST-STRATEGY §9). */
async function mockBff(
  page: Page,
  opts: { role: string; environment: string },
): Promise<MockState> {
  const state: MockState = { submits: [] }
  const jobs: object[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && pathname === '/api/bulk/error-class') {
        state.submits.push(route.request().postDataJSON())
        jobs.push(JOB)
        await route.fulfill({ json: JOB })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: opts.role, engineRoles: { eng1: opts.role } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [engine(opts.environment)] })
      } else if (pathname === '/api/triage') {
        await route.fulfill({
          json: {
            asOf: '2026-07-07T03:32:00Z',
            statusCounts: {},
            errorGroups: [GROUP],
            perEngine: { eng1: { ok: true } },
            engines: [engine(opts.environment)],
          },
        })
      } else if (pathname === '/api/bulk/job-9') {
        await route.fulfill({ json: JOB_DETAIL })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: jobs })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

test('retry group: Tier-3 modal, coordinates-only submit, drawer opens focused on the job', async ({
  page,
}) => {
  const state = await mockBff(page, { role: 'RESPONDER', environment: 'dev' })
  await page.goto('/')

  // The affordance sits next to the defKey:vN count and is enabled for a RESPONDER.
  const open = page.getByRole('button', { name: 'Retry group' })
  await expect(open).toBeEnabled()
  await open.click()

  // Tier-3 restatement: signature, scope, count — and the honesty line that the BINDING
  // member list is resolved server-side.
  const modal = page.getByRole('dialog', { name: /Retry group/ })
  await expect(modal).toBeVisible()
  await expect(modal.getByText('java.lang.ArithmeticException')).toBeVisible()
  await expect(modal.getByText('/ by zero')).toBeVisible()
  await expect(modal.getByText(/3 failing instances/)).toBeVisible()
  await expect(modal.getByText(/resolved\s+server-side at dispatch/)).toBeVisible()

  // Reason ladder: the restating confirm unlocks only on a ≥10-char reason (dev: no token).
  const confirm = modal.getByRole('button', { name: 'Retry group — payment v3' })
  await expect(confirm).toBeDisabled()
  await modal.getByLabel(/Reason/).fill('INC-4711: upstream data fixed, drain the class')
  await expect(confirm).toBeEnabled()
  await confirm.click()

  // THE invariant: coordinates only — no items array, no instance ID in the body.
  expect(state.submits).toHaveLength(1)
  const body = state.submits[0] as Record<string, unknown>
  expect(body).toMatchObject({
    signatureHash: HASH,
    algoVersion: 1,
    processDefinitionKey: 'payment',
    definitionVersion: 3,
    engineId: 'eng1',
    reason: 'INC-4711: upstream data fixed, drain the class',
  })
  expect(body['items']).toBeUndefined()
  expect(JSON.stringify(body)).not.toContain('p-1')

  // The handoff: modal gone, drawer open, the fresh job auto-expanded (its per-item
  // detail — hydrated from GET /api/bulk/job-9 — is visible without any extra click).
  await expect(modal).toHaveCount(0)
  const drawer = page.getByRole('complementary', { name: 'Operations drawer' })
  await expect(drawer).toBeVisible()
  await expect(drawer.getByText('retry-job')).toBeVisible()
  await expect(drawer.getByText('eng1:p-1')).toBeVisible()
  await expect(drawer.getByText('eng1:p-2')).toBeVisible()
})

test('retry group is greyed for a VIEWER with the gate named', async ({ page }) => {
  const state = await mockBff(page, { role: 'VIEWER', environment: 'dev' })
  await page.goto('/')

  const button = page.getByRole('button', { name: 'Retry group' })
  await expect(button).toBeDisabled()
  await expect(button).toHaveAttribute('title', /RESPONDER/)
  expect(state.submits).toEqual([])
})

test('on a PROD engine the confirm stays locked until the definition key is typed', async ({
  page,
}) => {
  const state = await mockBff(page, { role: 'ADMIN', environment: 'prod' })
  await page.goto('/')

  await page.getByRole('button', { name: 'Retry group' }).click()
  const modal = page.getByRole('dialog', { name: /Retry group/ })
  await expect(modal.getByText('a PRODUCTION engine')).toBeVisible()

  // Reason alone must NOT unlock on prod (corrective-actions §3: bulk on prod never
  // dispatches on a bare confirm) — the typed token is the stable definition key.
  const confirm = modal.getByRole('button', { name: 'Retry group — payment v3' })
  await modal.getByLabel(/Reason/).fill('INC-4711: upstream data fixed, drain the class')
  await expect(confirm).toBeDisabled()
  await modal.getByLabel(/Type the definition key/).fill('payment')
  await expect(confirm).toBeEnabled()

  // Cancel out — nothing dispatched.
  await modal.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(modal).toHaveCount(0)
  expect(state.submits).toEqual([])
})

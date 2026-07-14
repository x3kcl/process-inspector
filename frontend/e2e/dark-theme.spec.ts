// #104 slice 2b (R-UXQ-08): real axe-core color-contrast verification of the dark palette —
// the part of "contrast re-verified" that actually matters. Hermetic (predicate route, never
// the '**/api/**' glob — TEST-STRATEGY §9), reusing the same fixture shapes as
// retry-group.spec.ts / filter-bulk.spec.ts / deadletter-delete.spec.ts so these states are
// proven-representative rather than invented. Dark mode is forced via an init script that
// seeds localStorage before any app code runs — more reliable in Playwright than clicking
// through ThemeToggle first, and exercises the exact "persisted override" path the toggle
// itself writes to.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

function engine(environment: string) {
  return {
    id: 'eng1',
    name: environment === 'prod' ? 'Payments PROD' : 'Payments DEV',
    environment,
    mode: 'FULL',
    reachable: true,
    capabilities: { changeState: true },
  }
}

const HASH = 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2'

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

const SEARCH_ROWS = [
  {
    compositeId: 'eng1:p-1',
    engineId: 'eng1',
    processInstanceId: 'p-1',
    businessKey: 'order-1',
    processDefinitionKey: 'payment',
    definitionVersion: 3,
    status: 'FAILED',
    flags: { hasDeadLetterJobs: true },
    protectedInstance: false,
    startTime: '2026-07-07T03:00:00Z',
  },
  {
    compositeId: 'eng1:p-2',
    engineId: 'eng1',
    processInstanceId: 'p-2',
    businessKey: 'order-2',
    processDefinitionKey: 'payment',
    definitionVersion: 3,
    status: 'SUSPENDED',
    flags: { hasDeadLetterJobs: false },
    protectedInstance: true,
    startTime: '2026-07-07T03:01:00Z',
  },
]

const DEAD_LETTER_JOB = {
  id: 'dl-1',
  elementId: 'chargeCard',
  elementName: 'Charge card',
  exceptionMessage:
    'java.lang.RuntimeException: gateway timeout\n\tat com.acme.Charge(Charge.java:42)',
  retries: 0,
  processInstanceId: 'p-1',
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

// Union of the fixture shapes retry-group.spec.ts / filter-bulk.spec.ts / deadletter-
// delete.spec.ts already prove out — reused here rather than invented, per the task brief.
async function mockBff(
  page: Page,
  opts: { role: string; environment: string },
): Promise<{ submits: unknown[] }> {
  const state = { submits: [] as unknown[] }
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
      } else if (method === 'POST' && pathname === '/api/search') {
        await route.fulfill({
          json: {
            rows: SEARCH_ROWS,
            perEngine: { eng1: { ok: true, fetched: 2, total: 2 } },
            statusCounts: { FAILED: 1, SUSPENDED: 1 },
          },
        })
      } else if (pathname === '/api/instances/eng1/p-1/jobs') {
        await route.fulfill({
          json: { executable: [], timer: [], suspended: [], deadLetter: [DEAD_LETTER_JOB] },
        })
      } else if (pathname === '/api/instances/eng1/p-1/actions/delete-deadletter') {
        await route.fulfill({
          json: {
            outcome: 'ok',
            httpStatus: 200,
            deltaStatement: 'Dead-letter job dl-1 deleted. The execution is orphaned.',
            auditId: 'audit-1',
          },
        })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: opts.role, engineRoles: { eng1: opts.role } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [engine(opts.environment)] })
      } else if (pathname === '/api/triage') {
        await route.fulfill({
          json: {
            asOf: '2026-07-07T03:32:00Z',
            statusCounts: { FAILED: 3 },
            errorGroups: [GROUP],
            perEngine: { eng1: { ok: true } },
            engines: [engine(opts.environment)],
          },
        })
      } else if (pathname === '/api/bulk/job-9') {
        await route.fulfill({ json: JOB_DETAIL })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: jobs })
      } else if (pathname === '/api/views' || pathname === '/api/recents') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

/** Force dark BEFORE any app code runs — the exact persisted-override path ThemeToggle writes. */
async function forceDarkTheme(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem('inspector.theme', 'dark')
  })
}

test('triage page renders clean in dark mode (topbar, engine strip, error-group card)', async ({
  page,
}) => {
  await forceDarkTheme(page)
  await mockBff(page, { role: 'RESPONDER', environment: 'dev' })
  await page.goto('/')

  await expect(page.getByRole('button', { name: 'Retry group' })).toBeVisible()
  // The toggle itself reflects the forced state — proves the switch mechanism, not just CSS.
  await expect(page.getByRole('button', { name: 'Dark', pressed: true })).toBeVisible()
  await scanA11y(page, 'triage page (dark)')
})

test('search results grid renders clean in dark mode (AG Grid rows, status chips)', async ({
  page,
}) => {
  await forceDarkTheme(page)
  await mockBff(page, { role: 'RESPONDER', environment: 'dev' })
  await page.goto('/search?status=FAILED&definitionKey=payment')

  await expect(page.getByRole('row', { name: /order-1/ })).toBeVisible()
  await expect(page.getByRole('row', { name: /order-2/ })).toBeVisible()
  await scanA11y(page, 'search results grid (dark)')
})

test('instance detail — errors & jobs tab renders clean in dark mode (dead-letter lane)', async ({
  page,
}) => {
  await forceDarkTheme(page)
  await mockBff(page, { role: 'ADMIN', environment: 'dev' })
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  const lane = page.locator('details.lane-deadLetter')
  await expect(lane).toBeVisible()
  await scanA11y(page, 'instance detail errors-jobs tab (dark)')
})

test('delete dead-letter job modal (form controls) renders clean in dark mode', async ({
  page,
}) => {
  await forceDarkTheme(page)
  await mockBff(page, { role: 'ADMIN', environment: 'dev' })
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  const lane = page.locator('details.lane-deadLetter')
  await expect(lane).toBeVisible()
  await lane.getByRole('button', { name: 'Delete', exact: true }).click()

  const modal = page.getByRole('dialog')
  await expect(modal).toBeVisible()
  await scanA11y(page, 'delete dead-letter job modal open (dark)')
})

test('operations drawer after a retry-group dispatch renders clean in dark mode', async ({
  page,
}) => {
  await forceDarkTheme(page)
  const state = await mockBff(page, { role: 'RESPONDER', environment: 'dev' })
  await page.goto('/')

  await page.getByRole('button', { name: 'Retry group' }).click()
  const modal = page.getByRole('dialog', { name: /Retry group/ })
  await expect(modal).toBeVisible()
  await scanA11y(page, 'retry group modal open (dark)')
  await modal
    .getByLabel(/Why are you doing this/)
    .fill('INC-4711: upstream data fixed, drain the class')
  await modal.getByRole('button', { name: 'Retry group — payment v3' }).click()
  expect(state.submits).toHaveLength(1)

  const drawer = page.getByRole('complementary', { name: 'Operations drawer' })
  await expect(drawer).toBeVisible()
  await scanA11y(page, 'operations drawer after dispatch (dark)')
})

// v1.x #5 sibling diff (SPEC §5.2): the Comparison tab against a mocked BFF. Proves the whole
// loop — failed vitals → "compare" CTA → auto-suggested nearest sibling → three-way diff
// (variables + timing + divergence legend) → manual sibling override. Historic-only on the
// backend; here we only assert the rendered comparison surface.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = { id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }

const VITALS = {
  compositeId: 'eng1:inst-failed',
  engineId: 'eng1',
  processInstanceId: 'inst-failed',
  definitionKey: 'payment',
  definitionVersion: 3,
  processDefinitionId: 'payment:3:abc',
  startTime: '2026-07-07T07:00:00Z',
  status: 'FAILED',
  flags: { ended: false, suspended: false, hasDeadLetterJobs: true, hasFailingJobs: false },
  whyStuck: {
    exceptionFirstLine: 'java.lang.ArithmeticException: / by zero',
    failingActivityId: 'charge',
    failureTime: '2026-07-07T07:05:00Z',
    deadLetterJobs: 1,
    retryingJobs: 0,
  },
}

const DIFF_GOOD = {
  subject: {
    processInstanceId: 'inst-failed',
    businessKey: 'ORD-1',
    startTime: '2026-07-07T07:00:00Z',
    ended: false,
  },
  sibling: {
    processInstanceId: 'good-1',
    businessKey: 'ORD-0',
    endTime: '2026-07-07T06:00:00Z',
    durationMs: 12000,
    ended: true,
  },
  sameDefinition: true,
  previewCappedPresent: false,
  variables: [
    {
      name: 'amount',
      change: 'CHANGED',
      subject: { name: 'amount', type: 'integer', value: 100, truncated: false },
      sibling: { name: 'amount', type: 'integer', value: 250, truncated: false },
    },
    {
      name: 'divisor',
      change: 'ONLY_IN_SUBJECT',
      subject: { name: 'divisor', type: 'integer', value: 0, truncated: false },
    },
    {
      name: 'region',
      change: 'SAME',
      subject: { name: 'region', type: 'string', value: 'EU', truncated: false },
      sibling: { name: 'region', type: 'string', value: 'EU', truncated: false },
    },
  ],
  path: {
    subjectPath: [],
    siblingPath: [],
    onlyInSubject: ['charge'],
    onlyInSibling: ['refund'],
    common: ['start'],
  },
  timings: [
    {
      activityId: 'start',
      activityName: 'start',
      subjectMs: 5,
      siblingMs: 5,
      deltaMs: 0,
      subjectOccurrences: 1,
      siblingOccurrences: 1,
      subjectUnfinished: false,
    },
    {
      activityId: 'charge',
      activityName: 'charge',
      siblingMs: 80,
      subjectOccurrences: 1,
      siblingOccurrences: 1,
      subjectUnfinished: true,
    },
  ],
}

const DIFF_MANUAL = {
  ...DIFF_GOOD,
  sibling: {
    processInstanceId: 'manual-1',
    businessKey: 'ORD-9',
    endTime: '2026-07-05T10:00:00Z',
    ended: true,
  },
  variables: [
    {
      name: 'amount',
      change: 'ONLY_IN_SIBLING',
      sibling: { name: 'amount', type: 'integer', value: 999, truncated: false },
    },
  ],
}

interface MockState {
  diffRequests: string[]
}

async function mockBff(page: Page): Promise<MockState> {
  const state: MockState = { diffRequests: [] }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const base = '/api/instances/eng1/inst-failed'
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === base) {
        await route.fulfill({ json: VITALS })
      } else if (pathname === `${base}/diagram`) {
        await route.fulfill({
          json: { xml: '<definitions/>', activeActivityIds: [], deadLetterActivityIds: [] },
        })
      } else if (pathname === `${base}/nearest-sibling`) {
        await route.fulfill({
          json: {
            found: true,
            sibling: {
              processInstanceId: 'good-1',
              businessKey: 'ORD-0',
              endTime: '2026-07-07T06:00:00Z',
            },
            candidatesScanned: 3,
            definitionId: 'payment:3:abc',
            processDefinitionKey: 'payment',
            definitionVersion: 3,
          },
        })
      } else if (pathname === `${base}/diff/good-1`) {
        state.diffRequests.push('good-1')
        await route.fulfill({ json: DIFF_GOOD })
      } else if (pathname === `${base}/diff/manual-1`) {
        state.diffRequests.push('manual-1')
        await route.fulfill({ json: DIFF_MANUAL })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

test('the compare CTA opens a three-way diff against the auto-suggested sibling', async ({
  page,
}) => {
  const state = await mockBff(page)
  await page.goto('/inspect/eng1/inst-failed')

  // The failure strip offers the one-click comparison.
  const cta = page.getByRole('button', { name: /Compare with a sibling/ })
  await expect(cta).toBeVisible()
  await scanA11y(page, 'failed instance vitals with compare CTA')
  await cta.click()

  // Auto-suggested nearest sibling drives the diff without any manual input.
  await expect(page.getByText('auto-suggested', { exact: false })).toBeVisible()
  await expect(page.locator('.compare-header')).toContainText('good-1')
  expect(state.diffRequests).toContain('good-1')

  // Variables: the changed row and the subject-only row are surfaced (identical ones hidden).
  const varTable = page.locator('.var-diff-table')
  await expect(varTable).toContainText('amount')
  await expect(varTable).toContainText('divisor')
  await expect(varTable).not.toContainText('region') // SAME, hidden by default
  await expect(page.locator('.change-changed')).toBeVisible()

  // Timing: the stalled step is called out; the divergence legend renders.
  await expect(page.locator('.timing-stall-mark')).toBeVisible()
  await expect(page.getByText('only the failed run')).toBeVisible()
  await scanA11y(page, 'three-way diff loaded with variable and timing panels')
})

test('a manually entered sibling overrides the suggestion and re-runs the diff', async ({
  page,
}) => {
  const state = await mockBff(page)
  await page.goto('/inspect/eng1/inst-failed?tab=comparison')

  await expect(page.locator('.compare-header')).toContainText('good-1')
  await scanA11y(page, 'diff auto-suggested against nearest sibling')

  await page.getByLabel('sibling process instance id').fill('manual-1')
  await page.getByRole('button', { name: 'Compare', exact: true }).click()

  await expect(page.locator('.compare-header')).toContainText('manual-1')
  await expect(page.getByText('auto-suggested', { exact: false })).toHaveCount(0)
  expect(state.diffRequests).toContain('manual-1')
  // The override is a shareable deep link.
  await expect(page).toHaveURL(/sibling=manual-1/)
  await scanA11y(page, 'diff re-run against manually overridden sibling')
})

test('a cross-definition sibling is flagged, never silently compared', async ({ page }) => {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const base = '/api/instances/eng1/inst-failed'
      if (pathname === '/api/me')
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      else if (pathname === '/api/engines') await route.fulfill({ json: [ENGINE] })
      else if (pathname === base) await route.fulfill({ json: VITALS })
      else if (pathname === `${base}/diagram`)
        await route.fulfill({
          json: { xml: '<definitions/>', activeActivityIds: [], deadLetterActivityIds: [] },
        })
      else if (pathname === `${base}/nearest-sibling`)
        await route.fulfill({ json: { found: false, candidatesScanned: 0 } })
      else if (pathname === `${base}/diff/other-def`)
        await route.fulfill({ json: { ...DIFF_GOOD, sameDefinition: false } })
      else if (pathname === '/api/bulk') await route.fulfill({ json: [] })
      else await route.fulfill({ json: {} })
    },
  )
  await page.goto('/inspect/eng1/inst-failed?tab=comparison')

  // No auto-suggestion: the manual input is the only path.
  await expect(page.getByText(/No completed instance of this definition/)).toBeVisible()
  await scanA11y(page, 'comparison tab, no auto-suggested sibling found')
  await page.getByLabel('sibling process instance id').fill('other-def')
  await page.getByRole('button', { name: 'Compare', exact: true }).click()

  await expect(page.getByText(/different definition version/)).toBeVisible()
  await scanA11y(page, 'cross-definition sibling flagged, diff not silently shown')
})

// v1.x #2 smokes: select-all-matching-filter bulk + SSE-driven drawer hydration.
// Hermetic (every /api call fulfilled below). Two invariants protected here:
//   1. SERVER-SIDE RE-RESOLUTION — the filter submit carries the SearchRequest criteria
//      only; no instance ID ever crosses the wire from the browser.
//   2. ID-ONLY SSE — the drawer updates off the stream's bulk-job signal (refetch),
//      not off the 2.5s active poll (relaxed to 30s while the stream is live).
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
  }
}

const ROWS = [
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
    status: 'FAILED',
    flags: { hasDeadLetterJobs: true },
    protectedInstance: false,
    startTime: '2026-07-07T03:01:00Z',
  },
]

const RUNNING_JOB = {
  id: 'job-77',
  verb: 'retry-job',
  state: 'RUNNING',
  submittedBy: 'responder',
  submittedAt: '2026-07-07T03:33:00Z',
  reason: 'INC-4712: drain everything matching the filter',
  totalItems: 37,
  tallies: { pending: 30, ok: 7 },
}

const COMPLETED_JOB = { ...RUNNING_JOB, state: 'COMPLETED', tallies: { ok: 37 } }

interface MockState {
  /** Bodies of every POST /api/bulk/filter — the wire-contract evidence. */
  submits: unknown[]
  /** GET /api/bulk hit count — SSE hydration shows as a refetch, not a tight poll. */
  jobListReads: number
}

// Fulfills every /api call. Predicate (never the '**/api/**' glob): the glob would also
// hijack Vite's /src/api/* module requests and brick the dev server (TEST-STRATEGY §9).
async function mockBff(
  page: Page,
  opts: { role: string; environment: string },
): Promise<MockState> {
  const state: MockState = { submits: [], jobListReads: 0 }
  let submitted = false
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (pathname === '/api/bulk/events') {
        // The stream: one id-only bulk-job event, then the emitter window closes and the
        // browser EventSource reconnects on its own (retry hint keeps the loop calm).
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: 'retry: 1000\n\nevent:bulk-job\ndata:job-77\n\n',
        })
      } else if (method === 'POST' && pathname === '/api/bulk/filter') {
        state.submits.push(route.request().postDataJSON())
        submitted = true
        await route.fulfill({ json: RUNNING_JOB })
      } else if (method === 'POST' && pathname === '/api/search') {
        await route.fulfill({
          json: {
            rows: ROWS,
            perEngine: { eng1: { ok: true, fetched: 2, total: 37 } },
            statusCounts: { FAILED: 37 },
          },
        })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: opts.role, engineRoles: { eng1: opts.role } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [engine(opts.environment)] })
      } else if (pathname === '/api/bulk/job-77') {
        await route.fulfill({ json: submitted ? COMPLETED_JOB : RUNNING_JOB })
      } else if (pathname === '/api/bulk') {
        // First read answers RUNNING; every read after the submit answers COMPLETED —
        // the drawer flipping to COMPLETED proves a REFETCH happened (SSE-driven).
        state.jobListReads += 1
        await route.fulfill({ json: submitted ? [COMPLETED_JOB] : [] })
      } else if (pathname === '/api/views' || pathname === '/api/recents') {
        // Saved-views / recents chrome now renders on every search page (V6 relational store) —
        // an empty array keeps ViewChips/RecentSearchList from erroring in this unrelated smoke.
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

test('select-all-matching: criteria-only submit, drawer hydrates off the SSE signal', async ({
  page,
}) => {
  const state = await mockBff(page, { role: 'RESPONDER', environment: 'dev' })
  await page.goto('/search?status=FAILED&definitionKey=payment')

  // The grid rendered a page of a 37-instance match — the standalone affordance appears
  // as soon as rows exist, without touching a checkbox.
  const affordance = page.getByRole('button', { name: /Select all ~37 matching filter/ })
  await expect(affordance).toBeVisible()
  await scanA11y(page, 'search results grid with select-all-matching affordance')
  await affordance.click()

  // Filter scope: verbs derive from the status chips; a pure FAILED filter offers retry.
  const bar = page.getByRole('toolbar', { name: 'bulk actions' })
  await expect(bar.getByText(/resolved\s+server-side at execution/)).toBeVisible()
  const retry = bar.getByRole('button', { name: 'Retry dead-letter jobs' })
  await expect(retry).toBeEnabled()
  await scanA11y(page, 'bulk actions toolbar open')
  await retry.click()

  // The modal restates the criteria and the snapshot-vs-execution honesty line.
  const modal = page.getByRole('dialog', { name: /every instance matching/ })
  await expect(modal).toBeVisible()
  await expect(modal.getByText('FAILED', { exact: true })).toBeVisible()
  await expect(modal.getByText('payment', { exact: true })).toBeVisible()
  await expect(modal.getByText(/server-resolved filter at execution time/)).toBeVisible()
  await scanA11y(page, 'filter bulk retry modal open')

  const confirm = modal.getByRole('button', { name: /Retry dead-letter jobs — all matching/ })
  await expect(confirm).toBeDisabled()
  await modal
    .getByLabel(/Why are you doing this/)
    .fill('INC-4712: drain everything matching the filter')
  await expect(confirm).toBeEnabled()
  await confirm.click()

  // THE invariant: the body is the criteria + verb + reason — never an ID list.
  expect(state.submits).toHaveLength(1)
  const body = state.submits[0] as Record<string, unknown>
  expect(body['verb']).toBe('retry-job')
  expect(body['reason']).toBe('INC-4712: drain everything matching the filter')
  expect(body['criteria']).toMatchObject({ statuses: ['FAILED'], processDefinitionKey: 'payment' })
  expect(body['items']).toBeUndefined()
  expect(JSON.stringify(body)).not.toContain('p-1')

  // Drawer handoff + SSE hydration: the stream's id-only event triggers the refetch that
  // shows the settled job — well inside the 30s live-mode poll floor, so a fast flip can
  // only have come from the stream.
  const drawer = page.getByRole('complementary', { name: 'Operations drawer' })
  await expect(drawer).toBeVisible()
  await expect(drawer.getByText('COMPLETED')).toBeVisible({ timeout: 10_000 })
  await scanA11y(page, 'operations drawer after filter bulk completion')
})

test('a prod engine in filter scope demands the typed definition key', async ({ page }) => {
  const state = await mockBff(page, { role: 'ADMIN', environment: 'prod' })
  await page.goto('/search?status=FAILED&definitionKey=payment')

  await page.getByRole('button', { name: /Select all ~37 matching filter/ }).click()
  await page.getByRole('button', { name: 'Retry dead-letter jobs' }).click()

  const modal = page.getByRole('dialog', { name: /every instance matching/ })
  await expect(modal.getByText(/PRODUCTION engine/)).toBeVisible()
  await scanA11y(page, 'filter bulk modal on prod engine')

  // Reason alone must NOT unlock on prod — the typed token is the definition key
  // (never the raceable count: the members are re-resolved at execution time).
  const confirm = modal.getByRole('button', { name: /all matching the filter/ })
  await modal
    .getByLabel(/Why are you doing this/)
    .fill('INC-4712: drain everything matching the filter')
  await expect(confirm).toBeDisabled()
  await modal.getByLabel(/Type/).fill('payment')
  await expect(confirm).toBeEnabled()

  await modal.getByRole('button', { name: 'Cancel', exact: true }).click()
  expect(state.submits).toEqual([])
})

test('mixed status chips grey the incompatible verbs with the offender named', async ({ page }) => {
  await mockBff(page, { role: 'RESPONDER', environment: 'dev' })
  await page.goto('/search?status=FAILED,RETRYING&definitionKey=payment')

  await page.getByRole('button', { name: /Select all ~37 matching filter/ }).click()
  const retry = page.getByRole('button', { name: 'Retry dead-letter jobs' })
  await expect(retry).toBeDisabled()
  await expect(retry).toHaveAttribute('title', /RETRYING/)
  await scanA11y(page, 'bulk toolbar with incompatible verb disabled')
})

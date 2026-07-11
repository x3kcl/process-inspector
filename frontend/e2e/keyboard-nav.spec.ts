// R-UXQ-02 (usability W1#5, baseline theme T4): the FIND→FIX arc must be completable
// keyboard-only. Hermetic (every /api call fulfilled below). Invariants protected here:
//   1. Enter on a focused results-grid cell opens the instance detail (same route as
//      double-click), and the affordance carries a VISIBLE hint next to the grid.
//   2. The detail tablist roves per the ARIA APG: ArrowLeft/ArrowRight move focus
//      (manual activation — arrows alone never fire the lazy tab fetches).
//   3. After a route change focus never rests on <body> — it lands on the new route's
//      main heading / landmark.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const ENGINE = { id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }

const ROW = {
  compositeId: 'eng1:p-1',
  engineId: 'eng1',
  processInstanceId: 'p-1',
  businessKey: 'ORD-77',
  processDefinitionKey: 'payment',
  definitionVersion: 3,
  status: 'ACTIVE',
  flags: {},
  protectedInstance: false,
  startTime: '2026-07-09T10:00:00Z',
}

const VITALS = {
  compositeId: 'eng1:p-1',
  engineId: 'eng1',
  processInstanceId: 'p-1',
  definitionKey: 'payment',
  definitionVersion: 3,
  processDefinitionId: 'payment:3:abc',
  startTime: '2026-07-09T10:00:00Z',
  status: 'ACTIVE',
  flags: { ended: false, suspended: false, hasDeadLetterJobs: false, hasFailingJobs: false },
}

/** Fulfills every /api call. URL predicate (never the '**​/api/**' glob — TEST-STRATEGY §9). */
async function mockBff(page: Page): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      const base = '/api/instances/eng1/p-1'
      if (method === 'POST' && pathname === '/api/search') {
        await route.fulfill({
          json: {
            rows: [ROW],
            perEngine: { eng1: { ok: true, fetched: 1, total: 1 } },
            statusCounts: { ACTIVE: 1 },
          },
        })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === base) {
        await route.fulfill({ json: VITALS })
      } else if (pathname === `${base}/diagram`) {
        await route.fulfill({
          json: { xml: '<definitions/>', activeActivityIds: [], deadLetterActivityIds: [] },
        })
      } else if (pathname === `${base}/variables`) {
        await route.fulfill({ json: { processVariables: [], executionScopes: [] } })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        // Every other endpoint on these pages is a list (saved views, recents, ops-drawer
        // jobs) — an empty array keeps the surrounding chrome from erroring.
        await route.fulfill({ json: [] })
      }
    },
  )
}

test('Enter on a focused grid cell opens the detail route, and the hint is visible', async ({
  page,
}) => {
  await mockBff(page)
  await page.goto('/search?definitionKey=payment')

  const cell = page.getByText('ORD-77')
  await expect(cell).toBeVisible()

  // The open affordance is discoverable without a mouse hover or a manual.
  await expect(page.getByText(/opens the focused row/)).toBeVisible()

  // A single click only FOCUSES the cell (click-select is off; open is double-click) —
  // the open itself happens on the keyboard.
  await cell.click()
  await expect(page).toHaveURL(/\/search/)
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL(/\/inspect\/eng1\/p-1/)
  await expect(page.getByText('eng1:p-1')).toBeVisible()
})

test('after the grid→detail route change, focus never rests on <body>', async ({ page }) => {
  await mockBff(page)
  await page.goto('/search?definitionKey=payment')
  const cell = page.getByText('ORD-77')
  await cell.click()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/inspect\/eng1\/p-1/)

  // The route-focus effect runs on the tick after navigation.
  await expect
    .poll(async () =>
      page.evaluate(() => {
        const active = document.activeElement
        return active === null || active === document.body ? 'body' : active.tagName
      }),
    )
    .toBe('MAIN')
})

test('detail tabs rove with arrow keys per the ARIA APG (manual activation)', async ({ page }) => {
  await mockBff(page)
  await page.goto('/inspect/eng1/p-1')

  const variables = page.getByRole('tab', { name: 'Variables' })
  const errorsJobs = page.getByRole('tab', { name: 'Errors & Jobs' })
  const audit = page.getByRole('tab', { name: 'Audit & Notes' })
  await expect(variables).toHaveAttribute('aria-selected', 'true')

  // Roving tabindex: only the active tab sits in the Tab order.
  await expect(variables).toHaveAttribute('tabindex', '0')
  await expect(errorsJobs).toHaveAttribute('tabindex', '-1')

  await variables.focus()
  await page.keyboard.press('ArrowRight')
  await expect(errorsJobs).toBeFocused()
  // Arrow movement alone never activates (each tab lazy-loads on open).
  await expect(variables).toHaveAttribute('aria-selected', 'true')

  // ArrowLeft wraps from the first tab to the last.
  await page.keyboard.press('ArrowLeft')
  await expect(variables).toBeFocused()
  await page.keyboard.press('ArrowLeft')
  await expect(audit).toBeFocused()

  // Enter activates the focused tab (native button semantics).
  await page.keyboard.press('Enter')
  await expect(audit).toHaveAttribute('aria-selected', 'true')
  await expect(page).toHaveURL(/tab=audit/)
})

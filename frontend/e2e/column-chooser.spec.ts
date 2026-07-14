// R-UXQ-09 (#104 slice 4/6): the results-grid column-visibility chooser. Hermetic (every
// /api call intercepted below), fixture shapes reused from filter-bulk.spec.ts /
// dark-theme.spec.ts (TEST-STRATEGY §9 — never the '**/api/**' glob, it would also hijack
// Vite's /src/api/* module requests). Covers: open the chooser, hide a column, confirm it
// disappears from the grid, confirm the choice survives a reload, reset to default, and an
// axe scan with the panel OPEN (the new interactive surface this slice adds).
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
    status: 'SUSPENDED',
    flags: { hasDeadLetterJobs: false },
    protectedInstance: true,
    startTime: '2026-07-07T03:01:00Z',
  },
]

// Fulfills every /api call (predicate route, never the '**/api/**' glob — TEST-STRATEGY §9).
async function mockBff(page: Page, opts: { role: string; environment: string }): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && pathname === '/api/search') {
        await route.fulfill({
          json: {
            rows: ROWS,
            perEngine: { eng1: { ok: true, fetched: 2, total: 2 } },
            statusCounts: { FAILED: 1, SUSPENDED: 1 },
          },
        })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: opts.role, engineRoles: { eng1: opts.role } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [engine(opts.environment)] })
      } else if (pathname === '/api/views' || pathname === '/api/recents') {
        await route.fulfill({ json: [] })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

// No storage-clearing beforeEach needed: each Playwright test runs in a fresh browser
// context (isolated storage) by default, so localStorage always starts empty here.

test('hide a column via the chooser, it disappears from the grid, and the choice persists across reload', async ({
  page,
}) => {
  await mockBff(page, { role: 'RESPONDER', environment: 'dev' })
  await page.goto('/search?status=FAILED,SUSPENDED&definitionKey=payment')

  await expect(page.getByRole('row', { name: /order-1/ })).toBeVisible()
  // Business Key is a HIDEABLE column — visible by default.
  const grid = page.locator('.results-grid')
  await expect(grid.getByRole('columnheader', { name: 'Business Key' })).toBeVisible()

  const toggle = page.getByRole('button', { name: 'Columns ▾' })
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await toggle.click()
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')

  const panel = page.getByRole('region', { name: 'Choose visible columns' })
  await expect(panel).toBeVisible()
  // Locked columns (R-UXQ-09 honesty indicators + the row-open affordance) are never offered.
  await expect(panel.getByLabel('Engine')).toHaveCount(0)
  await expect(panel.getByLabel('Status')).toHaveCount(0)
  await expect(panel.getByText(/always shown/)).toBeVisible()

  // The new interactive surface this slice adds — scanned with the panel OPEN.
  await scanA11y(page, 'column chooser panel open')

  await panel.getByLabel('Business Key').uncheck()
  await expect(grid.getByRole('columnheader', { name: 'Business Key' })).toHaveCount(0)
  // Escape closes the panel again.
  await page.keyboard.press('Escape')
  await expect(panel).toBeHidden()

  // Persists across a reload — the same localStorage-backed choice re-hydrates the grid.
  // (The row's accessible name no longer includes the business key once that column is
  // hidden, so this checks by process ID — still present in the locked Process ID column.)
  await page.reload()
  await expect(page.getByRole('row', { name: /p-1/ })).toBeVisible()
  await expect(grid.getByRole('columnheader', { name: 'Business Key' })).toHaveCount(0)

  // Reset to default brings it back.
  await page.getByRole('button', { name: 'Columns ▾' }).click()
  await page.getByRole('button', { name: 'Reset to default' }).click()
  await expect(grid.getByRole('columnheader', { name: 'Business Key' })).toBeVisible()
})

test('clicking outside the open chooser panel closes it', async ({ page }) => {
  await mockBff(page, { role: 'RESPONDER', environment: 'dev' })
  await page.goto('/search?status=FAILED,SUSPENDED&definitionKey=payment')
  await expect(page.getByRole('row', { name: /order-1/ })).toBeVisible()

  await page.getByRole('button', { name: 'Columns ▾' }).click()
  const panel = page.getByRole('region', { name: 'Choose visible columns' })
  await expect(panel).toBeVisible()

  await page.locator('.results-grid').click({ position: { x: 5, y: 5 } })
  await expect(panel).toBeHidden()
})

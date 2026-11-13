// #497 smoke: the operations drawer shows a hierarchy migration job's per-level progress in a
// real browser — deepest level first, a Level column, and the "blocked by child" cause on the
// parents a failed child left not run. Hermetic (every /api call fulfilled below).
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const JOB = {
  id: 'job-h',
  verb: 'migrate-instance',
  state: 'COMPLETED',
  submittedBy: 'admin',
  submittedAt: '2026-11-12T12:00:00Z',
  reason: 'INC-497: move the tree off the bad deploy',
  scopeLabel: 'hierarchy migrate · plan 7d3c6a0e',
  totalItems: 4,
  tallies: { ok: 1, failed: 1, not_run: 2 },
}

const ITEMS = [
  {
    ordinal: 0,
    engineId: 'eng1',
    instanceId: 'leaf-1',
    jobRef: 'analysis-b',
    state: 'failed',
    detail: 'instance-suspended: activate it first',
    level: 2,
    parentInstanceId: 'mid',
  },
  {
    ordinal: 1,
    engineId: 'eng1',
    instanceId: 'leaf-2',
    jobRef: 'analysis-b',
    state: 'ok',
    detail: 'migrated',
    level: 2,
    parentInstanceId: 'mid',
  },
  {
    ordinal: 2,
    engineId: 'eng1',
    instanceId: 'mid',
    jobRef: 'analysis-a',
    state: 'not_run',
    detail: 'blocked by child eng1:leaf-1 (failed: instance-suspended: activate it first)',
    level: 1,
    parentInstanceId: 'root',
  },
  {
    ordinal: 3,
    engineId: 'eng1',
    instanceId: 'root',
    jobRef: 'analysis-root',
    state: 'not_run',
    detail: 'blocked by child eng1:mid (not_run)',
    level: 0,
  },
]

// Predicate, never the '**/api/**' glob: the glob would also hijack Vite's /src/api/* module
// requests and brick the dev server (TEST-STRATEGY §9).
async function mockBff(page: Page, items: unknown[]) {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/bulk/events') {
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: 'retry: 1000\n\n',
        })
      } else if (pathname === '/api/bulk/job-h') {
        await route.fulfill({ json: { ...JOB, items } })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [JOB] })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'ADMIN', engineRoles: { eng1: 'ADMIN' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({
          json: [
            { id: 'eng1', name: 'Payments DEV', environment: 'dev', mode: 'FULL', reachable: true },
          ],
        })
      } else if (pathname === '/api/search') {
        await route.fulfill({ json: { rows: [], perEngine: {}, statusCounts: {} } })
      } else if (pathname === '/api/views' || pathname === '/api/recents') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

async function openJob(page: Page) {
  await page.goto('/search')
  await page.getByRole('button', { name: /Operations/ }).click()
  const drawer = page.getByRole('complementary', { name: 'Operations drawer' })
  await expect(drawer).toBeVisible()
  await drawer.getByText('migrate-instance').first().click()
  return drawer
}

test('a hierarchy migration job shows its levels deepest first and why a parent did not run', async ({
  page,
}) => {
  await mockBff(page, ITEMS)
  const drawer = await openJob(page)

  const levels = drawer.getByTestId('hierarchy-levels')
  await expect(levels).toBeVisible()
  await expect(levels).toContainText('Children first, then parent')
  await expect(levels.getByRole('listitem')).toHaveText([
    'level 2 — 1 of 2 migrated · 1 failed',
    'level 1 — 0 of 1 migrated · 1 not run',
    'level 0 — 0 of 1 migrated · 1 not run',
  ])

  // The Level column sits between the ordinal and the instance, one value per item.
  await expect(drawer.getByRole('columnheader', { name: 'Level' })).toBeVisible()
  const midRow = drawer.getByRole('row').filter({ hasText: 'eng1:mid' }).first()
  await expect(midRow.getByRole('cell').nth(1)).toHaveText('1')
  await expect(midRow).toContainText('blocked by child eng1:leaf-1 (failed')
  await expect(drawer.getByRole('row').filter({ hasText: 'eng1:root' }).first()).toContainText(
    'blocked by child eng1:mid (not_run)',
  )
  await scanA11y(page, 'operations drawer with a hierarchy migration job')
})

test('a cohort migrate job shows no level block and no Level column', async ({ page }) => {
  await mockBff(
    page,
    ITEMS.map((item) => ({ ...item, level: undefined, parentInstanceId: undefined })),
  )
  const drawer = await openJob(page)

  await expect(
    drawer.getByRole('cell', { name: 'instance-suspended: activate it first', exact: true }),
  ).toBeVisible()
  await expect(drawer.getByTestId('hierarchy-levels')).toHaveCount(0)
  await expect(drawer.getByRole('columnheader', { name: 'Level' })).toHaveCount(0)
})

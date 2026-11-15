// #672 inspect layout: the detail page scrolls as one page; the tab panel never collapses
// into a tiny inner scroller and the diagram height is bounded. Hermetic (mocked BFF).
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const VITALS = {
  compositeId: 'eng1:inst-1',
  engineId: 'eng1',
  processInstanceId: 'inst-1',
  definitionKey: 'payment',
  definitionVersion: 3,
  processDefinitionId: 'payment:3:abc',
  startTime: '2026-07-07T07:00:00Z',
  status: 'RUNNING',
  flags: { ended: false, suspended: false, hasDeadLetterJobs: false, hasFailingJobs: false },
}

const VARS = Array.from({ length: 14 }, (_, i) => ({
  name: `var${String(i)}`,
  type: 'string',
  value: `value-${String(i)}`,
}))

async function mockBff(page: Page) {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const base = '/api/instances/eng1/inst-1'
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({
          json: [{ id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }],
        })
      } else if (pathname === base) {
        await route.fulfill({ json: VITALS })
      } else if (pathname === `${base}/variables`) {
        await route.fulfill({ json: { executionScopes: [], processVariables: VARS } })
      } else if (pathname === `${base}/diagram`) {
        await route.fulfill({
          json: { xml: '<definitions/>', activeActivityIds: [], deadLetterActivityIds: [] },
        })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

async function open(page: Page, width: number, height: number) {
  await page.setViewportSize({ width, height })
  await mockBff(page)
  await page.goto('/inspect/eng1/inst-1')
  await expect(page.locator('.diagram-canvas').first()).toBeVisible()
  await expect(page.getByRole('tabpanel')).toContainText('var0', { timeout: 20_000 })
}

test('1440x900: tab panel is not a collapsed inner scroller', async ({ page }) => {
  await open(page, 1440, 900)
  const m = await page.getByRole('tabpanel').evaluate((el) => ({
    h: el.getBoundingClientRect().height,
    scrollH: el.scrollHeight,
    clientH: el.clientHeight,
  }))
  expect(m.h).toBeGreaterThanOrEqual(240)
  // whole content visible inside the panel: no inner vertical scrolling
  expect(m.scrollH).toBeLessThanOrEqual(m.clientH + 1)
})

test('1024x700: tablist is reachable by scrolling .inspect', async ({ page }) => {
  await open(page, 1024, 700)
  const tablist = page.getByRole('tablist').first()
  await page.locator('.inspect').evaluate((el) => {
    el.scrollTo(0, 0)
  })
  await tablist.scrollIntoViewIfNeeded()
  await expect(tablist).toBeInViewport()
  // last tab content row can be scrolled clear of the fixed Operations drawer
  await page.locator('.inspect').evaluate((el) => {
    el.scrollTo(0, el.scrollHeight)
  })
  const last = await page.getByRole('tabpanel').getByText('var13').first().boundingBox()
  const drawerEl = page.locator('.ops-drawer')
  const drawer = (await drawerEl.count()) > 0 ? await drawerEl.first().boundingBox() : null
  expect(last).not.toBeNull()
  if (drawer && last) expect(last.y + last.height).toBeLessThanOrEqual(drawer.y + 1)
})

test('1920x1080: diagram canvas height is bounded', async ({ page }) => {
  await open(page, 1920, 1080)
  const h = await page
    .locator('.diagram-canvas')
    .first()
    .evaluate((el) => el.clientHeight)
  expect(h).toBeLessThanOrEqual(480)
})

// Appended RED case (LR-2: a later RED commit only appends): axe on the long-tab page, which
// also satisfies the per-spec scanA11y() coverage guard without editing the cases above.
test('1440x900: the long-tab inspect page passes axe', async ({ page }) => {
  const { scanA11y } = await import('./a11y')
  await open(page, 1440, 900)
  await scanA11y(page, 'inspect page at 1440x900 with a long tab')
})

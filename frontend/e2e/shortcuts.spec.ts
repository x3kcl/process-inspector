// #536 / #537: ⌘K focuses the omnibox from any page, ? lists the bindings, and a
// deep-linked instance renders a URL-derived breadcrumb that keeps ?tab=.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = { id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }

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

async function mockBff(page: Page): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === '/api/instances/eng1/p-1') {
        await route.fulfill({ json: VITALS })
      } else if (pathname === '/api/instances/eng1/p-1/diagram') {
        await route.fulfill({
          json: { xml: '<definitions/>', activeActivityIds: [], deadLetterActivityIds: [] },
        })
      } else if (pathname === '/api/instances/eng1/p-1/variables') {
        await route.fulfill({
          json: { source: 'RUNTIME', processVariables: [], executionScopes: [] },
        })
      } else {
        await route.fulfill({ json: [] })
      }
    },
  )
}

test('Ctrl+K focuses the omnibox from the audit page and ? opens the sheet', async ({ page }) => {
  await mockBff(page)
  await page.goto('/audit')
  await page.keyboard.press('Control+k')
  const omnibox = page.locator('#omnibox')
  await expect(omnibox).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(omnibox).not.toBeFocused()
  await page.keyboard.press('?')
  const sheet = page.getByRole('dialog', { name: 'Keyboard shortcuts' })
  await expect(sheet).toBeVisible()
  await expect(sheet.getByText('Focus the omnibox and select its text')).toBeVisible()
  await scanA11y(page, 'shortcut sheet')
})

test('a deep-linked instance shows breadcrumbs and keeps ?tab=', async ({ page }) => {
  await mockBff(page)
  await page.goto('/inspect/eng1/p-1?tab=variables')
  const trail = page.getByRole('navigation', { name: 'Breadcrumb' })
  await expect(trail.getByRole('link', { name: 'Payments DEV' })).toHaveAttribute(
    'href',
    '/search?engines=eng1',
  )
  await expect(trail.getByRole('link', { name: 'payment', exact: true })).toHaveAttribute(
    'href',
    '/definitions/eng1/payment/versions',
  )
  await expect(trail.getByRole('link', { name: 'v3' })).toHaveAttribute(
    'href',
    /definitionKey=payment/,
  )
  await expect(trail.locator('[aria-current="page"]')).toHaveText('p-1')
  expect(page.url()).toContain('tab=variables')
  await scanA11y(page, 'instance breadcrumbs')
})

// #743: the audited reveal. An OPERATOR clicks "Reveal apiToken" beside the hidden value, the SPA
// POSTs the reveal route once and shows the value with a copy button; a VIEWER sees the button
// disabled with its reason. Both states stay axe-clean. Hermetic (mocked BFF).
import { expect, test, type Page } from '@playwright/test'
import { scanA11y } from './a11y'

const SECRET = 'tok-743-e2e'

const VITALS = {
  engineId: 'eng1',
  processInstanceId: 'inst-1',
  compositeId: 'eng1:inst-1',
  businessKey: 'order-743',
  status: 'ACTIVE',
  flags: { ended: false, suspended: false },
  definitionKey: 'orderFlow',
  definitionName: 'Order flow',
  definitionVersion: 1,
  processDefinitionId: 'orderFlow:1:abc',
  startTime: '2026-11-17T08:00:00Z',
  currentActivities: [],
  waitingFor: [],
}

const VARIABLES = {
  source: 'RUNTIME',
  executionScopes: [],
  processVariables: [
    {
      name: 'amount',
      type: 'integer',
      value: 743,
      masked: false,
      truncated: false,
      scope: 'global',
    },
    { name: 'apiToken', type: 'string', masked: true, truncated: false, scope: 'global' },
  ],
}

async function stub(page: Page, role: string): Promise<string[]> {
  const reveals: string[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role, engineRoles: { eng1: role } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({
          json: [{ id: 'eng1', name: 'Engine 1', environment: 'dev', reachable: true }],
        })
      } else if (pathname === '/api/instances/eng1/inst-1/variables/apiToken/reveal') {
        reveals.push(route.request().method())
        await route.fulfill({
          json: { name: 'apiToken', type: 'string', value: SECRET, scope: 'global' },
          headers: { 'Cache-Control': 'no-store' },
        })
      } else if (pathname === '/api/instances/eng1/inst-1/variables') {
        await route.fulfill({ json: VARIABLES })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else if (pathname === '/api/instances/eng1/inst-1') {
        await route.fulfill({ json: VITALS })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return reveals
}

test('an operator reveals a masked value once, with a copy button, axe-clean', async ({ page }) => {
  const reveals = await stub(page, 'OPERATOR')
  await page.goto('/inspect/eng1/inst-1?tab=variables')
  const row = page.getByRole('row', { name: /apiToken/ })
  await expect(row).toContainText('hidden — secret-named')

  await row.getByRole('button', { name: 'Reveal apiToken' }).click()

  await expect(row).toContainText(SECRET)
  await expect(row.getByRole('button', { name: /copy value/i })).toBeVisible()
  expect(reveals).toEqual(['POST'])
  await scanA11y(page, 'variables tab with a revealed secret-named variable')
})

test('a viewer sees the reveal disabled with its reason', async ({ page }) => {
  const reveals = await stub(page, 'VIEWER')
  await page.goto('/inspect/eng1/inst-1?tab=variables')
  const row = page.getByRole('row', { name: /apiToken/ })

  await expect(row.getByRole('button', { name: 'Reveal apiToken' })).toBeDisabled()
  await expect(row).toContainText('Requires OPERATOR — you are VIEWER')
  expect(reveals).toEqual([])
  await scanA11y(page, 'variables tab with the reveal gated for a viewer')
})

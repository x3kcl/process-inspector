// #742: a secret-named variable reaches the SPA masked (masked: true, no value, type kept). The
// Variables tab shows the distinct "hidden — secret-named" state as text, the plain variable
// beside it keeps its value, and the page stays axe-clean. Hermetic (mocked BFF).
import { expect, test } from '@playwright/test'
import { scanA11y } from './a11y'

const VITALS = {
  engineId: 'eng1',
  processInstanceId: 'inst-1',
  compositeId: 'eng1:inst-1',
  businessKey: 'order-742',
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
      value: 742,
      masked: false,
      truncated: false,
      scope: 'global',
    },
    { name: 'apiToken', type: 'string', masked: true, truncated: false, scope: 'global' },
  ],
}

test('a masked variable reads as hidden — secret-named and the tab passes axe', async ({
  page,
}) => {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'VIEWER', engineRoles: { eng1: 'VIEWER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({
          json: [{ id: 'eng1', name: 'Engine 1', environment: 'dev', reachable: true }],
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
  await page.goto('/inspect/eng1/inst-1?tab=variables')
  const row = page.getByRole('row', { name: /apiToken/ })
  await expect(row).toContainText('hidden — secret-named')
  await expect(page.getByRole('row', { name: /amount/ })).toContainText('742')
  await scanA11y(page, 'variables tab with a masked secret-named variable')
})

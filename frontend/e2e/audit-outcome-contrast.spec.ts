// #702: the amber "re-queued" audit outcome (retry-job + ok) must meet WCAG AA contrast on
// the light theme. The nightly real-stack arc scans it on the instance Audit & Notes tab; this
// hermetic twin pins the same badge on the Ops log so the PR lane catches it.
import { expect, test } from '@playwright/test'
import { scanA11y } from './a11y'

const RETRY_OK = {
  id: '00000000-0000-0000-0000-000000000001',
  ts: '2026-11-16T02:30:00Z',
  actor: 'operator',
  action: 'retry-job',
  engineId: 'eng1',
  instanceId: 'p-1',
  outcome: 'ok',
  httpStatus: 200,
  reason: 'divisor fixed',
}

test('the re-queued outcome on the Ops log passes axe colour contrast', async ({ page }) => {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'OPERATOR', engineRoles: { eng1: 'OPERATOR' } } })
      } else if (pathname === '/api/audit') {
        await route.fulfill({ json: [RETRY_OK] })
      } else {
        await route.fulfill({ json: [] })
      }
    },
  )
  await page.goto('/audit')
  await expect(page.getByText('re-queued', { exact: true })).toBeVisible()
  await scanA11y(page, 'Ops log with a re-queued retry-job')
})

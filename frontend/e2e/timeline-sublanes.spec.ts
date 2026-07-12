// v1.x #4 timeline sub-lanes (SPEC §4): the Timeline tab against a mocked BFF. Proves the
// rendered DOM (the only true test of an accessibility tree) — a call-activity nests its
// child's activities as an indented sub-lane, a dead-lettered node carries a NON-HUE badge
// (SPEC §10a: icon + literal state word, not color), a phantom (async-rollback) node renders
// with "timing unknown", and an isCapped node shows an honest truncation warning.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = { id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }

const VITALS = {
  compositeId: 'eng1:inst1',
  engineId: 'eng1',
  processInstanceId: 'inst1',
  definitionKey: 'demoParent',
  definitionVersion: 1,
  processDefinitionId: 'demoParent:1:abc',
  startTime: '2026-07-07T07:00:00Z',
  status: 'FAILED',
  flags: { ended: false, suspended: false, hasDeadLetterJobs: true, hasFailingJobs: false },
}

const TIMELINE = {
  total: 2,
  truncated: false,
  activities: [
    {
      id: 'a-start',
      activityId: 'start',
      activityType: 'startEvent',
      startTime: '2026-07-07T07:00:00Z',
      endTime: '2026-07-07T07:00:01Z',
      durationMs: 1000,
    },
    {
      id: 'a-call',
      activityId: 'callPayment',
      activityType: 'callActivity',
      startTime: '2026-07-07T07:00:01Z',
      calledProcessInstanceId: 'child-1',
      isCapped: true,
      children: [
        {
          id: 'c-start',
          activityId: 'childStart',
          activityType: 'startEvent',
          startTime: '2026-07-07T07:00:02Z',
          endTime: '2026-07-07T07:00:03Z',
          durationMs: 1000,
        },
        // Phantom: a dead-lettered async task whose history row rolled back — no times at all.
        { activityId: 'charge', activityType: 'serviceTask', liveJobState: 'FAILED' },
      ],
    },
  ],
}

async function mockBff(page: Page): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const base = '/api/instances/eng1/inst1'
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
      } else if (pathname === `${base}/timeline`) {
        await route.fulfill({ json: TIMELINE })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

test('the timeline nests a call-activity sub-lane, annotates the failure non-hue, and warns on truncation', async ({
  page,
}) => {
  await mockBff(page)
  await page.goto('/inspect/eng1/inst1?tab=timeline')

  // The timeline is an accessibility tree.
  const tree = page.getByRole('tree')
  await expect(tree).toBeVisible()
  await scanA11y(page, 'timeline tab loaded with tree')

  // The child's activity nests one level deep (aria-level 2) and is visually indented.
  await expect(page.getByRole('treeitem', { name: /childStart/, level: 2 })).toBeVisible()
  await expect(page.locator('.tl-label--nested').first()).toBeVisible()

  // NON-HUE annotation (SPEC §10a): the FAILED node shows the literal word + the 🛑 glyph,
  // not merely a red bar. Scope to the timeline badge so we don't match the vitals status.
  const failedBadge = page.locator('.tl-badge--FAILED')
  await expect(failedBadge).toContainText('FAILED')
  await expect(failedBadge).toContainText('🛑')

  // The phantom node (async rollback, no history row) renders with honest "timing unknown".
  const phantom = page.locator('.tl-phantom-row')
  await expect(phantom).toContainText('charge')
  await expect(phantom).toContainText('timing unknown')
  await expect(phantom.locator('.tl-bar--phantom')).toBeVisible()

  // Honest truncation: the capped call-activity's sub-lane ends with a visible warning.
  await expect(page.getByText(/Sub-tree truncated/)).toBeVisible()
})

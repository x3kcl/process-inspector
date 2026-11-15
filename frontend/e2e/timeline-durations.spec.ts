// #675 timeline durations: ms-precision text, proportional bars, sequenceFlow rows folded.
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
  total: 4,
  truncated: false,
  activities: [
    {
      id: 'a1',
      activityId: 'fast',
      activityName: 'Fast step',
      activityType: 'serviceTask',
      startTime: '2026-07-07T07:00:00.000Z',
      endTime: '2026-07-07T07:00:00.143Z',
      durationMs: 143,
    },
    {
      id: 'f1',
      activityId: 'f_gw_timeout',
      activityType: 'sequenceFlow',
      startTime: '2026-07-07T07:00:00.143Z',
      endTime: '2026-07-07T07:00:00.143Z',
      durationMs: 0,
    },
    {
      id: 'a2',
      activityId: 'slow',
      activityName: 'Slow step',
      activityType: 'serviceTask',
      startTime: '2026-07-07T07:00:00.143Z',
      endTime: '2026-07-07T07:00:01.573Z',
      durationMs: 1430,
    },
    {
      id: 'f2',
      activityId: 'f_end',
      activityType: 'sequenceFlow',
      startTime: '2026-07-07T07:00:01.573Z',
      endTime: '2026-07-07T07:00:01.573Z',
      durationMs: 0,
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

test('durations are ms-precise, bars proportional, flows folded behind a segmented control', async ({
  page,
}) => {
  await mockBff(page)
  await page.goto('/inspect/eng1/inst1?tab=timeline')

  await scanA11y(page, 'timeline durations tab loaded')

  const fast = page.getByRole('treeitem', { name: /Fast step/ })
  await expect(fast).toContainText('143 ms')
  await expect(fast).not.toContainText('0s')
  await expect(page.getByRole('treeitem', { name: /Slow step/ })).toContainText('1.4 s')

  // Flows hidden by default, count stated; the control reveals them.
  await expect(page.getByText('f_gw_timeout')).toHaveCount(0)
  await expect(page.getByText('2 sequence flows hidden')).toBeVisible()
  await page.getByRole('button', { name: 'Steps + flows' }).click()
  await expect(page.getByRole('treeitem', { name: /sequenceFlow/ })).toHaveCount(2)

  // 10x duration ratio -> visibly different bar widths.
  const width = async (name: RegExp): Promise<number> =>
    (await page.getByRole('treeitem', { name }).locator('.tl-bar').boundingBox())?.width ?? 0
  const w1 = await width(/Fast step/)
  const w2 = await width(/Slow step/)
  expect(w2).toBeGreaterThan(w1 * 5)
})

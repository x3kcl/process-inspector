// v1.x #7 smoke: the External Worker lane in the Errors & Jobs tab. Hermetic. The invariant
// these tests protect is CAPABILITY GATING (graceful degradation): on a ≥ 6.8 engine the lane
// renders with the worker lock owner; on a pre-6.8 engine the lane is absent AND the BFF
// external-worker endpoint is never called (no empty lane, no spinner, no blind request).
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

function engine(externalWorkerJobs: boolean) {
  return {
    id: 'eng1',
    name: 'Payments DEV',
    environment: 'dev',
    mode: 'FULL',
    reachable: true,
    capabilities: { changeState: true, externalWorkerJobs },
  }
}

const EW_JOB = {
  id: 'ew-1',
  elementId: 'chargeViaWorker',
  elementName: 'Charge via worker',
  lockOwner: 'worker-3',
  lockExpirationTime: '2026-07-07T14:00:00Z',
  retries: 3,
}

interface MockState {
  externalWorkerCalls: number
}

/** Predicate route (never the '**​/api/**' glob — TEST-STRATEGY §9). */
async function mockBff(page: Page, opts: { externalWorkerJobs: boolean }): Promise<MockState> {
  const state: MockState = { externalWorkerCalls: 0 }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/instances/eng1/p-1/jobs/external-worker') {
        state.externalWorkerCalls += 1
        await route.fulfill({ json: opts.externalWorkerJobs ? [EW_JOB] : [] })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'OPERATOR', engineRoles: { eng1: 'OPERATOR' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [engine(opts.externalWorkerJobs)] })
      } else if (pathname === '/api/instances/eng1/p-1/jobs') {
        await route.fulfill({ json: { executable: [], timer: [], suspended: [], deadLetter: [] } })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

test('capable engine: the External Worker lane renders with the lock owner', async ({ page }) => {
  await mockBff(page, { externalWorkerJobs: true })
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  const lane = page.locator('details.lane-external-worker')
  await expect(lane).toBeVisible()
  await expect(lane.getByText('External worker')).toBeVisible()
  // The lock owner is the crux column for a "stuck worker" incident.
  await expect(lane.getByText('worker-3')).toBeVisible()
  await expect(lane.getByText('chargeViaWorker')).toBeVisible()
})

test('pre-6.8 engine: the lane is absent and the BFF endpoint is never called', async ({
  page,
}) => {
  const state = await mockBff(page, { externalWorkerJobs: false })
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  // The four management lanes still render — anchor on one so we know the tab mounted.
  await expect(page.locator('details.lane-deadLetter')).toBeVisible()
  // Graceful degradation: no external-worker lane, and no request ever left the browser.
  await expect(page.locator('details.lane-external-worker')).toHaveCount(0)
  expect(state.externalWorkerCalls).toBe(0)
})

// Regression guard: the BPMN instance-level Delete dead-letter job flow (Errors & Jobs tab).
// delete-deadletter is tier 3, so the BFF requires a reason (≥ 10 chars) UNCONDITIONALLY. This
// test proves the reason the DestructiveModal collects actually reaches the request body — a
// prior version dropped it (`run` sent only { jobId }), so every real delete was refused
// `reason-required`. Hermetic (predicate route, never the '**​/api/**' glob — TEST-STRATEGY §9).
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const ENGINE = {
  id: 'eng1',
  name: 'Payments DEV',
  environment: 'dev',
  mode: 'FULL',
  reachable: true,
  capabilities: { changeState: true },
}

const DEAD_LETTER_JOB = {
  id: 'dl-1',
  elementId: 'chargeCard',
  elementName: 'Charge card',
  exceptionMessage:
    'java.lang.RuntimeException: gateway timeout\n\tat com.acme.Charge(Charge.java:42)',
  retries: 0,
  processInstanceId: 'p-1',
}

interface MockState {
  deleteBody: Record<string, unknown> | null
}

async function mockBff(page: Page, role: string): Promise<MockState> {
  const state: MockState = { deleteBody: null }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role, engineRoles: { eng1: role } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === '/api/instances/eng1/p-1/jobs') {
        await route.fulfill({
          json: { executable: [], timer: [], suspended: [], deadLetter: [DEAD_LETTER_JOB] },
        })
      } else if (pathname === '/api/instances/eng1/p-1/actions/delete-deadletter') {
        state.deleteBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          json: {
            outcome: 'ok',
            httpStatus: 200,
            deltaStatement: 'Dead-letter job dl-1 deleted. The execution is orphaned.',
            auditId: 'audit-1',
          },
        })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

test('an ADMIN delete sends the required reason in the request body (tier-3 reason discipline)', async ({
  page,
}) => {
  const state = await mockBff(page, 'ADMIN')
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  // The dead-letter lane auto-opens (it has a row); the Delete button opens the tier-3 modal.
  const lane = page.locator('details.lane-deadLetter')
  await expect(lane).toBeVisible()
  await lane.getByRole('button', { name: 'Delete', exact: true }).click()

  const modal = page.getByRole('dialog')
  await expect(modal).toBeVisible()

  // The confirm is disabled until a ≥10-char reason lands (DEV engine → no typed token gate).
  const confirm = modal.getByRole('button', { name: /Delete dead-letter job/ })
  await expect(confirm).toBeDisabled()
  await modal.getByRole('textbox').first().fill('vendor confirmed the charge already settled')
  await expect(confirm).toBeEnabled()
  await confirm.click()

  // The crux: the reason reaches the wire (previously dropped → the BFF would 400 reason-required).
  await expect(page.getByText(/deleted/)).toBeVisible()
  expect(state.deleteBody).toMatchObject({
    jobId: 'dl-1',
    reason: 'vendor confirmed the charge already settled',
  })
})

test('a RESPONDER cannot delete a dead-letter job — the tier-3 button is gated', async ({
  page,
}) => {
  await mockBff(page, 'RESPONDER')
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  const lane = page.locator('details.lane-deadLetter')
  await expect(lane.getByRole('button', { name: 'Retry job' })).toBeEnabled()
  await expect(lane.getByRole('button', { name: 'Delete', exact: true })).toBeDisabled()
})

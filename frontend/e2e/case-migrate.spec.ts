// Case migration on-ramp smokes (#500). Hermetic — every /api call is fulfilled below.
// What these protect: on a 6.8 engine the two by-definition doors are greyed WITH the version
// reason (never hidden) and nothing is sent; on a 7.x engine the batch door sends exactly one
// by-definition call and the page shows the engine batch.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = {
  id: 'eng1',
  name: 'Cases DEV',
  environment: 'dev',
  mode: 'FULL',
  reachable: true,
  capabilities: { changeState: true, migration: true, scopeType: true },
}

const GATE = "needs Flowable 7.x (engine 'eng1' is 6.8.0)"

const versions = (gate: string | null) => ({
  engineId: 'eng1',
  key: 'claimCase',
  latestVersion: 2,
  totalVersions: 2,
  complete: true,
  definitionRoutesGate: gate,
  versions: [
    {
      caseDefinitionId: 'def-2',
      version: 2,
      name: 'Claim case',
      latest: true,
      runningCaseCount: 0,
      endedCaseCount: 0,
    },
    {
      caseDefinitionId: 'def-1',
      version: 1,
      name: 'Claim case',
      latest: false,
      runningCaseCount: 3,
      endedCaseCount: 2,
    },
  ],
})

const BATCH = {
  batchId: 'batch-1',
  status: 'completed',
  createTime: '2026-11-12T11:00:00Z',
  completeTime: '2026-11-12T11:00:12Z',
  fromCaseDefinitionId: 'def-1',
  toCaseDefinitionId: 'def-2',
  parts: 3,
  partsByStatus: { success: 3 },
  notSucceededCaseIds: [],
}

/** Fulfills every /api call and records each mutating case-migration call. */
async function mockBff(page: Page, gate: string | null, sent: string[]): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      const json = (body: unknown) =>
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
      if (method === 'POST') {
        sent.push(pathname)
        return json({
          auditId: 'a-1',
          outcome: 'ok',
          engineHttpStatus: 200,
          deltaStatement:
            'The engine accepted a batch migration of 3 running cases (batch batch-1). The batch runs in the engine.',
        })
      }
      if (pathname === '/api/me')
        return json({ username: 'admin', role: 'ADMIN', engineRoles: { eng1: 'ADMIN' } })
      if (pathname === '/api/engines') return json([ENGINE])
      if (pathname.endsWith('/versions')) return json(versions(gate))
      if (pathname.endsWith('/migration-batches')) return json([BATCH])
      return json([])
    },
  )
}

test('6.8: the by-definition doors are greyed with the version reason, the cohort door is not', async ({
  page,
}) => {
  const sent: string[] = []
  await mockBff(page, GATE, sent)
  await page.goto('/case-definitions/eng1/claimCase/versions')

  const row = page.getByRole('row', { name: /v1/ })
  await expect(row.getByRole('button', { name: 'Migrate cases…' })).toBeEnabled()
  const batch = row.getByRole('button', { name: 'Batch-migrate all running…' })
  const historic = row.getByRole('button', { name: 'Migrate ended cases…' })
  await expect(batch).toBeDisabled()
  await expect(historic).toBeDisabled()
  await expect(batch).toHaveAccessibleDescription(
    new RegExp(GATE.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  )
  await expect(page.getByRole('note').filter({ hasText: GATE })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Batches' })).toHaveCount(0)
  await scanA11y(page, 'case versions on a 6.8 engine')

  // The cohort modal greys the contains filter with the same reason.
  await row.getByRole('button', { name: 'Migrate cases…' }).click()
  await expect(page.getByRole('checkbox')).toBeDisabled()
  await scanA11y(page, 'case cohort modal on a 6.8 engine')
  expect(sent).toEqual([])
})

test('7.x: the batch door sends one by-definition call and the page lists the engine batch', async ({
  page,
}) => {
  const sent: string[] = []
  await mockBff(page, null, sent)
  await page.goto('/case-definitions/eng1/claimCase/versions')

  const row = page.getByRole('row', { name: /v1/ })
  await row.getByRole('button', { name: 'Batch-migrate all running…' }).click()
  const start = page.getByRole('button', { name: 'Start batch (3)' })
  await expect(start).toBeDisabled()
  await page.getByLabel(/Reason/).fill('move the claims to the fixed case model')
  await scanA11y(page, 'case batch modal on a 7.x engine')
  await start.click()

  await expect(page.getByText(/The engine accepted a batch migration/)).toBeVisible()
  expect(sent).toEqual(['/api/case-definitions/eng1/def-1/migrate-batch'])

  await row.getByRole('button', { name: 'Batches' }).click()
  const batches = page.getByRole('table', { name: 'Case migration batches' })
  await expect(batches).toContainText('batch-1')
  await expect(batches).toContainText('success 3')
  await scanA11y(page, 'case versions with the batch list on a 7.x engine')
})

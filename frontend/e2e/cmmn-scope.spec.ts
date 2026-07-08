// Case Inspector Phase 1: the Stage-0 "≥N CMMN jobs not triaged here" note becomes a
// drillable list. These tests prove the whole loop (note → "View jobs" → drawer with the
// enumerated CMMN dead-letters, ≥ lower-bound honesty preserved) against the real rendered
// UI with a mocked BFF.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const ENGINE = {
  id: 'eng1',
  name: 'Payments DEV',
  environment: 'dev',
  reachable: true,
  capabilities: { changeState: true, migration: true, externalWorkerJobs: true, scopeType: true, activityHistory: true },
}

interface Options {
  /** outOfScopeDeadletters count on eng1; when truncated the strip renders "≥". */
  count: number
  truncated: boolean
}

// Predicate route (never the '**​/api/**' glob — that would hijack Vite's /src/api/* module
// requests and brick the dev server, TEST-STRATEGY §9).
async function mockBff(page: Page, opts: Options): Promise<void> {
  const jobs = Array.from({ length: opts.count }, (_, i) => ({
    id: `job-${String(i)}`,
    caseInstanceId: `case-${String(i)}`,
    caseDefinitionId: 'def-uuid',
    planItemInstanceId: `pi-${String(i)}`,
    elementId: 'failingService',
    elementName: 'Failing service',
    retries: 0,
    exceptionMessage: 'Unknown property used in expression: ${nonExistentBean.doStuff()}',
    createTime: '2026-07-08T08:16:18.882+00:00',
  }))
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === '/api/triage') {
        await route.fulfill({
          json: {
            asOf: '2026-07-08T08:16:00Z',
            statusCounts: {},
            errorGroups: [],
            engines: [ENGINE],
            perEngine: {
              eng1: { ok: true, outOfScopeDeadletters: opts.count, deadletterTruncated: opts.truncated },
            },
          },
        })
      } else if (pathname === '/api/triage/engines/eng1/out-of-scope-deadletters') {
        await route.fulfill({ json: { jobs, truncated: opts.truncated, scanned: opts.count } })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

test('the out-of-scope note drills into the enumerated CMMN dead-letters', async ({ page }) => {
  await mockBff(page, { count: 2, truncated: false })
  await page.goto('/')

  const note = page.getByRole('note', { name: 'Out-of-scope dead-letters' })
  await expect(note).toContainText('2 CMMN jobs not triaged here')

  await note.getByRole('button', { name: 'View jobs' }).click()

  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('Out-of-scope dead-letters — eng1')
  await expect(dialog).toContainText('2 CMMN dead-letter jobs')
  await expect(dialog.getByText('Failing service')).toHaveCount(2)
  await expect(dialog).toContainText('nonExistentBean')
  await expect(dialog.getByText('case-0')).toBeVisible()
})

test('a truncated scan is a labeled lower bound in both the note and the drawer', async ({
  page,
}) => {
  await mockBff(page, { count: 3, truncated: true })
  await page.goto('/')

  const note = page.getByRole('note', { name: 'Out-of-scope dead-letters' })
  await expect(note).toContainText('≥3 CMMN jobs not triaged here')

  await note.getByRole('button', { name: 'View jobs' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('≥3 CMMN dead-letter jobs')
  await expect(dialog).toContainText('lower bound')
})

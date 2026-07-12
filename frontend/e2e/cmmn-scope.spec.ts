// Case Inspector Phase 1: the Stage-0 "≥N CMMN jobs not triaged here" note becomes a
// drillable list. These tests prove the whole loop (note → "View jobs" → drawer with the
// enumerated CMMN dead-letters, ≥ lower-bound honesty preserved) against the real rendered
// UI with a mocked BFF.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = {
  id: 'eng1',
  name: 'Payments DEV',
  environment: 'dev',
  reachable: true,
  capabilities: {
    changeState: true,
    migration: true,
    externalWorkerJobs: true,
    scopeType: true,
    activityHistory: true,
  },
}

interface Options {
  /** outOfScopeDeadletters count on eng1; when truncated the strip renders "≥". */
  count: number
  truncated: boolean
}

// Predicate route (never the '**/api/**' glob — that would hijack Vite's /src/api/* module
// requests and brick the dev server, TEST-STRATEGY §9).
async function mockBff(page: Page, opts: Options): Promise<void> {
  const jobs = Array.from({ length: opts.count }, (_, i) => ({
    id: `job-${String(i)}`,
    caseInstanceId: `case-${String(i)}`,
    caseDefinitionId: 'def-uuid',
    caseDefinitionKey: 'demoFailingCase',
    caseDefinitionName: 'Demo failing case',
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
              eng1: {
                ok: true,
                outOfScopeDeadletters: opts.count,
                deadletterTruncated: opts.truncated,
              },
            },
          },
        })
      } else if (pathname === '/api/triage/engines/eng1/cmmn-scope') {
        await route.fulfill({
          json: {
            // FAILED = distinct cases with a dead-letter job; each mock job has a unique case,
            // so it equals the job count (a lower bound when the scan truncated).
            lanes: { active: 7, failed: opts.count, completed: 12, terminated: 1 },
            deadletters: { jobs, truncated: opts.truncated, scanned: opts.count },
          },
        })
      } else if (pathname === '/api/resolve') {
        // The omnibox resolver answers a pasted Case id with a read-only CMMN_CASE match — no
        // compositeId/processInstanceId (a case is not a process instance). Case Inspector Phase 2
        // made it navigable: the row links to /case/{engineId}/{caseId}.
        await route.fulfill({
          json: {
            query: 'case-0',
            perEngine: { eng1: { ok: true } },
            matches: [
              {
                kind: 'CMMN_CASE',
                engineId: 'eng1',
                matchedId: 'case-0',
                definitionKey: 'demoFailingCase',
              },
            ],
          },
        })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else if (pathname === '/api/views' || pathname === '/api/recents') {
        // Saved-views / recents chrome now renders on every search page (V6 relational store) —
        // an empty array keeps ViewChips/RecentSearchList from erroring in this unrelated smoke.
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
  await scanA11y(page, 'landing with out-of-scope dead-letter note')

  await note.getByRole('button', { name: 'View jobs' }).click()

  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('CMMN scope — eng1')
  await scanA11y(page, 'CMMN scope modal open')
  // The modal band carries the engine's real risk tier (eng1 is DEV), NOT the bare "UNKNOWN"
  // fallback that read as a rendering bug in usability testing (Finding #3).
  await expect(dialog.getByText('DEV', { exact: true })).toBeVisible()
  await expect(dialog.getByText('UNKNOWN', { exact: true })).toHaveCount(0)

  // The scope-typed lane tiles (ACTIVE/FAILED/COMPLETED/TERMINATED — no SUSPENDED). Each tile is
  // count + label; assert per tile so a mis-mapped lane can't pass on a coincidental substring.
  const lanes = dialog.getByRole('list', { name: 'CMMN case lanes' })
  await expect(lanes.getByRole('listitem').filter({ hasText: 'ACTIVE' })).toContainText('7')
  await expect(lanes.getByRole('listitem').filter({ hasText: 'FAILED' })).toContainText('2')
  await expect(lanes.getByRole('listitem').filter({ hasText: 'COMPLETED' })).toContainText('12')
  await expect(lanes.getByRole('listitem').filter({ hasText: 'TERMINATED' })).toContainText('1')
  await expect(dialog.getByText('SUSPENDED', { exact: true })).toHaveCount(0)

  // The FAILED lane drills into the dead-letter jobs.
  await expect(dialog).toContainText('FAILED — dead-letter jobs')
  // The bare-uuid caseDefinitionId is shown as the resolved readable case type + key.
  await expect(dialog.getByText('Demo failing case')).toHaveCount(2)
  await expect(dialog).toContainText('(demoFailingCase)')
  await expect(dialog.getByText('Failing service')).toHaveCount(2)
  await expect(dialog).toContainText('nonExistentBean')
  await expect(dialog.getByText('case-0')).toBeVisible()
})

test('a pasted CMMN Case id resolves to a navigable read-only case-detail link', async ({
  page,
}) => {
  await mockBff(page, { count: 2, truncated: false })
  await page.goto('/')

  // Paste a Case id into the omnibox and resolve it.
  await page.getByPlaceholder(/paste an instance/).fill('case-0')
  await page.getByPlaceholder(/paste an instance/).press('Enter')

  // The resolve panel surfaces the CMMN case as a navigable, honestly-labelled row (Case
  // Inspector Phase 2 gave it a destination; Phase 1 rendered it as an inert dead-end).
  const match = page.getByRole('region', { name: 'Resolve results' }).getByRole('link')
  await expect(match).toContainText('CMMN case')
  await expect(match).toContainText('open the read-only case detail')
  await scanA11y(page, 'omnibox resolve results with CMMN case match')

  await match.click()
  await expect(page).toHaveURL(/\/case\/eng1\/case-0$/)
})

test('a truncated scan is a labeled lower bound in both the note and the drawer', async ({
  page,
}) => {
  await mockBff(page, { count: 3, truncated: true })
  await page.goto('/')

  const note = page.getByRole('note', { name: 'Out-of-scope dead-letters' })
  await expect(note).toContainText('≥3 CMMN jobs not triaged here')
  await scanA11y(page, 'landing note showing truncated lower bound')

  await note.getByRole('button', { name: 'View jobs' }).click()
  const dialog = page.getByRole('dialog')
  // The FAILED lane tile floors to a lower bound under a truncated scan, and so does the drill.
  const lanes = dialog.getByRole('list', { name: 'CMMN case lanes' })
  await expect(lanes.getByRole('listitem').filter({ hasText: 'FAILED' })).toContainText('≥3')
  await expect(dialog).toContainText('≥3')
  await expect(dialog).toContainText('lower bound')
  await scanA11y(page, 'CMMN scope modal with truncated lower bound')
})

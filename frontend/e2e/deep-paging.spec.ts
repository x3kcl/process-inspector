// v2 deep paging (docs/KWAY-PAGING.md §4) smokes: the "Load more" cursor chain.
// Hermetic (every /api call fulfilled below). Invariants protected here:
//   1. Load more surfaces ONLY when the response carries a nextCursor (overflow, time-ordered).
//   2. A "Load more" click APPENDS the next page (rows accumulate; ticked selection is NOT reset)
//      and re-sends the SAME filter body plus the opaque cursor — never an ID list.
//   3. A deep-paged set is a SNAPSHOT (the calm seam line), and the depth wall is a filter seam.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

function row(id: string, startTime: string) {
  return {
    compositeId: `eng1:${id}`,
    engineId: 'eng1',
    processInstanceId: id,
    businessKey: `order-${id}`,
    processDefinitionKey: 'payment',
    definitionVersion: 3,
    status: 'ACTIVE',
    flags: {},
    protectedInstance: false,
    startTime,
  }
}

const PAGE1 = [row('p-1', '2026-07-07T03:05:00Z'), row('p-2', '2026-07-07T03:04:00Z')]
const PAGE2 = [row('p-3', '2026-07-07T03:03:00Z'), row('p-4', '2026-07-07T03:02:00Z')]

interface MockState {
  /** Bodies of every POST /api/search — evidence the filter is re-sent with only a cursor added. */
  searches: Array<Record<string, unknown>>
}

// Fulfills every /api call. URL predicate (never the '**/api/**' glob, which would hijack Vite's
// /src/api/* module requests and brick the dev server — TEST-STRATEGY §9). The /api/search handler
// BRANCHES on the request body's cursor: page 1 hands back a nextCursor, page 2 ends the chain.
async function mockBff(
  page: Page,
  opts: { depthCappedOnPage2?: boolean } = {},
): Promise<MockState> {
  const state: MockState = { searches: [] }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && pathname === '/api/search') {
        const body = route.request().postDataJSON() as Record<string, unknown>
        state.searches.push(body)
        if (body.cursor === undefined || body.cursor === null) {
          // Page 1: an overflowing time-ordered result → the entry cursor.
          await route.fulfill({
            json: {
              rows: PAGE1,
              perEngine: { eng1: { ok: true, fetched: 2, total: 4 } },
              statusCounts: { ACTIVE: 4 },
              nextCursor: 'CURSOR-2',
              depthCapped: false,
            },
          })
        } else {
          // Page 2 (cursor present): the next page, end of stream, snapshot coherence.
          await route.fulfill({
            json: {
              rows: PAGE2,
              perEngine: { eng1: { ok: true, fetched: 2, total: 4 } },
              statusCounts: {},
              nextCursor: null,
              depthCapped: opts.depthCappedOnPage2 === true,
              pagingCoherence: 'snapshot',
            },
          })
        }
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({
          json: [
            { id: 'eng1', name: 'Payments DEV', environment: 'dev', mode: 'FULL', reachable: true },
          ],
        })
      } else {
        // Every other endpoint on this page is a list (saved views, recents, ops-drawer jobs) —
        // an empty array keeps the surrounding chrome from erroring while we exercise paging.
        await route.fulfill({ json: [] })
      }
    },
  )
  return state
}

test('Load more appends the next cursor page and shows the snapshot seam', async ({ page }) => {
  const state = await mockBff(page)
  await page.goto('/search?status=ACTIVE&definitionKey=payment')

  // Page 1: two rows, and the overflow entry cursor surfaces "Load more".
  await expect(page.getByText('2 instances', { exact: false })).toBeVisible()
  const loadMore = page.getByRole('button', { name: 'Load more' })
  await expect(loadMore).toBeVisible()
  await scanA11y(page, 'search results page 1 with Load more visible')

  await loadMore.click()

  // Page 2 appended → four rows; the chain ended so "Load more" is gone and the seam is shown.
  await expect(page.getByText('4 instances', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0)
  await expect(page.getByText(/Loaded more as of/)).toBeVisible()
  await scanA11y(page, 'search results after Load more appended page 2')

  // The second request re-sent the SAME filter plus the opaque cursor — never an ID list.
  expect(state.searches).toHaveLength(2)
  expect(state.searches[1].cursor).toBe('CURSOR-2')
  expect(state.searches[1].processDefinitionKey).toBe('payment')
  expect(JSON.stringify(state.searches[1])).not.toContain('eng1:p-1')
})

test('#167: the "X of Y fetched" progress line grows with Load more instead of staying frozen', async ({
  page,
}) => {
  await mockBff(page)
  await page.goto('/search?status=ACTIVE&definitionKey=payment')

  // Page 1: 2 of 4 fetched (the mock's own perEngine, unmodified by the fix — page 1 is correct
  // by construction either way; the bug only ever showed up after Load more).
  await expect(page.getByText('eng1 2 of 4 fetched', { exact: false })).toBeVisible()

  await page.getByRole('button', { name: 'Load more' }).click()

  // All 4 rows are now loaded (total: 4 in both mocked pages) — the progress line must reflect
  // the ACCUMULATED count, not stay frozen at page 1's "2 of 4". Once fetched catches up to
  // total the row no longer overflows, so the "X of Y fetched" line clears entirely (scoped to
  // that phrasing, not a bare engineId match — the grid itself legitimately shows "eng1" too).
  await expect(page.getByText(/of \d+ fetched/)).toHaveCount(0)
})

test('the depth wall offers a pre-filled time-bound filter seam', async ({ page }) => {
  await mockBff(page, { depthCappedOnPage2: true })
  await page.goto('/search?status=ACTIVE&definitionKey=payment')
  await page.getByRole('button', { name: 'Load more' }).click()

  // At the cap, the honest bridge: narrow by a time bound instead of paging deeper.
  await expect(page.getByText(/Reached the paging depth/)).toBeVisible()
  await expect(
    page.getByRole('button', { name: /Continue by narrowing to started before/ }),
  ).toBeVisible()
  await scanA11y(page, 'depth wall time-bound filter seam shown')
})

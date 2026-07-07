// v1.x saved views & recent searches: a view is a named URL search string, nothing more —
// these tests prove the whole loop (curated chip → replayed search → recents history →
// user-named view → delete) against the real rendered UI with a mocked BFF.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const ENGINE = {
  id: 'eng1',
  name: 'Payments DEV',
  environment: 'dev',
  reachable: true,
}

interface MockState {
  /** Bodies of every POST /api/search — proves a chip replays the exact URL state. */
  searches: unknown[]
}

/** Fulfills every /api call. Predicate (never the '**​/api/**' glob): the glob would also
 *  hijack Vite's /src/api/* module requests and brick the dev server (TEST-STRATEGY §9). */
async function mockBff(page: Page): Promise<MockState> {
  const state: MockState = { searches: [] }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && pathname === '/api/search') {
        state.searches.push(route.request().postDataJSON())
        await route.fulfill({
          json: {
            rows: [],
            statusCounts: {},
            perEngine: { eng1: { ok: true, total: 0, fetched: 0 } },
            criteriaEcho: ['status: FAILED'],
          },
        })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === '/api/triage') {
        await route.fulfill({
          json: {
            asOf: '2026-07-07T03:32:00Z',
            statusCounts: {},
            errorGroups: [],
            perEngine: { eng1: { ok: true } },
            engines: [ENGINE],
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

test('curated system views on the landing replay the exact URL state into Stage 1', async ({
  page,
}) => {
  const state = await mockBff(page)
  await page.goto('/')

  const section = page.getByRole('region', { name: 'Saved views' })
  await expect(section.getByRole('link')).toHaveText([
    'Failed (all engines)',
    'Failed in the last hour',
    'Suspended > 24h (by start time)',
    'Started in the last hour',
  ])

  // Honesty guardrail (R-SEM-05): the suspended view scopes by START time and says so.
  const suspended = section.getByRole('link', { name: /Suspended > 24h/ })
  await expect(suspended).toHaveAttribute('href', /startedBefore=/)
  await expect(suspended).toHaveAttribute('title', /no suspension timestamp/)
  // The failed-recently view rides failure time, never instance start.
  const failedHour = section.getByRole('link', { name: 'Failed in the last hour' })
  await expect(failedHour).toHaveAttribute('href', /failedAfter=/)

  await section.getByRole('link', { name: 'Failed (all engines)' }).click()
  await expect(page).toHaveURL(/\/search\?.*status=FAILED/)

  // The URL decoded into the request the BFF actually received — URL primacy end-to-end.
  await expect.poll(() => state.searches.length, { message: 'search should auto-execute' }).toBe(1)
  expect(state.searches[0]).toMatchObject({ statuses: ['FAILED'], sortBy: 'failureTime' })

  // The chip that produced this URL highlights as the active view in the Stage 1 strip.
  const strip = page.getByRole('navigation', { name: 'Views' })
  await expect(strip.getByRole('link', { name: 'Failed (all engines)' })).toHaveAttribute(
    'aria-current',
    'true',
  )
  await expect(strip.getByRole('link', { name: 'Started in the last hour' })).not.toHaveAttribute(
    'aria-current',
    'true',
  )
})

test('an executed search lands in recents; saving names the current view; delete removes it', async ({
  page,
}) => {
  await mockBff(page)

  // Execute a search by URL (the only way a search happens).
  await page.goto('/search?status=FAILED&definitionKey=taxOrder')
  await expect(page.getByText(/0 instances · as of/)).toBeVisible()

  // Save the current view under a user name — the rail has collapsed to chips by now.
  await page.getByRole('button', { name: 'Save current view…' }).click()
  await page.getByLabel('view name').fill('My stuck tax orders')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  const strip = page.getByRole('navigation', { name: 'Views' })
  const userChip = strip.getByRole('link', { name: 'My stuck tax orders' })
  await expect(userChip).toHaveAttribute('aria-current', 'true')

  // Back on the landing: the user view renders beside the curated ones, the executed
  // search shows in recents with its human-readable label.
  await page.goto('/')
  const section = page.getByRole('region', { name: 'Saved views' })
  await expect(section.getByRole('link', { name: 'My stuck tax orders' })).toBeVisible()
  await expect(section.getByRole('link', { name: 'FAILED · taxOrder' })).toBeVisible()

  // Delete affordance removes the user view (and survives the localStorage round-trip).
  await section.getByRole('button', { name: 'delete view My stuck tax orders' }).click()
  await expect(section.getByRole('link', { name: 'My stuck tax orders' })).toHaveCount(0)
  await page.reload()
  await expect(
    page
      .getByRole('region', { name: 'Saved views' })
      .getByRole('link', { name: 'FAILED · taxOrder' }),
  ).toBeVisible()
  await expect(
    page
      .getByRole('region', { name: 'Saved views' })
      .getByRole('link', { name: 'My stuck tax orders' }),
  ).toHaveCount(0)
})

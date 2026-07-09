// Team (shared) views (v2, SHARED-VIEWS.md): the curated canon an operator publishes for the team.
// Proves the picker rendering (TEAM tag, author/scope tooltip, dangling greying) and the deliberate
// "Publish to team…" second act, against the real rendered UI with a mocked BFF. Route mocks use a
// URL predicate (never the '**​/api/**' glob — it would hijack Vite's /src/api/* modules).
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const ENGINE = { id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }

interface MockState {
  publishes: unknown[]
}

async function mockBff(page: Page, role: string): Promise<MockState> {
  const state: MockState = { publishes: [] }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && pathname === '/api/team-views') {
        state.publishes.push(route.request().postDataJSON())
        await route.fulfill({
          json: {
            id: 99,
            name: 'My stuck tax orders',
            search: 'status=FAILED',
            scopeEngineId: '*',
            scopeTenantId: '*',
            author: 'op',
          },
        })
      } else if (method === 'GET' && pathname === '/api/team-views') {
        await route.fulfill({
          json: [
            {
              id: 1,
              name: 'Stuck payments in prod',
              search: 'status=FAILED&engines=eng1',
              scopeEngineId: 'eng1',
              scopeTenantId: '*',
              author: 'alice',
              description: 'the payments runbook',
            },
            {
              id: 2,
              name: 'Old orders view',
              search: 'status=FAILED&engines=gone',
              scopeEngineId: 'gone',
              scopeTenantId: '*',
              author: 'bob',
              danglingReason:
                'the engine "gone" this team view is scoped to is not currently available',
            },
          ],
        })
      } else if (pathname === '/api/views') {
        await route.fulfill({
          json: [
            {
              id: 7,
              name: 'My stuck tax orders',
              search: 'status=FAILED',
              createdAt: '2026-07-09T09:00:00Z',
            },
          ],
        })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role, engineRoles: { eng1: role } } })
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
      } else if (pathname === '/api/recents' || pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

test('team views render with a non-color TEAM tag; a dangling canon is greyed, not a live link', async ({
  page,
}) => {
  await mockBff(page, 'OPERATOR')
  await page.goto('/')

  const teamGroup = page.getByRole('group', { name: 'Team views' })
  // The healthy canon is a clickable link with the TEAM tag; the dangling one is NOT a link.
  await expect(teamGroup.getByRole('link', { name: 'Stuck payments in prod' })).toBeVisible()
  await expect(teamGroup.getByText('TEAM').first()).toBeVisible()
  await expect(teamGroup.getByRole('link', { name: 'Old orders view' })).toHaveCount(0)
  await expect(teamGroup.getByText('(scope unavailable)')).toBeVisible()

  // Clicking the healthy canon replays its exact URL state (URL primacy).
  await teamGroup.getByRole('link', { name: 'Stuck payments in prod' }).click()
  await expect(page).toHaveURL(/\/search\?.*status=FAILED/)
})

test('an operator publishes a private view to the team through the deliberate second act', async ({
  page,
}) => {
  const state = await mockBff(page, 'OPERATOR')
  await page.goto('/')

  const section = page.getByRole('region', { name: 'Saved views' })
  await section.getByRole('button', { name: 'Publish to team…' }).click()
  await section.getByLabel('runbook description').fill('retry after the gateway is back')
  await section.getByRole('button', { name: 'Publish', exact: true }).click()

  await expect.poll(() => state.publishes.length).toBe(1)
  expect(state.publishes[0]).toMatchObject({
    name: 'My stuck tax orders',
    description: 'retry after the gateway is back',
  })
})

test('publishing is greyed (never hidden) for a responder who lacks OPERATOR', async ({ page }) => {
  await mockBff(page, 'RESPONDER')
  await page.goto('/')

  const publish = page
    .getByRole('region', { name: 'Saved views' })
    .getByRole('button', { name: 'Publish to team…' })
  await expect(publish).toBeVisible()
  await expect(publish).toBeDisabled()
})

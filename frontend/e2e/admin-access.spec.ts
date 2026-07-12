// The IdP-Security S6 access-admin surface (docs/IDP-SECURITY.md §12, issue #85/#94 follow-up):
// the group→scope mapping at /admin/access. Proves the full grant lifecycle against the real
// rendered UI with a mocked BFF — a narrow (non-widening) grant applies immediately and can be
// revoked, while a widening grant (any fleet grant, per the escalation matrix) is proposed for
// four-eyes and only leaves the pending-proposal inbox once a second ACCESS_ADMIN approves it.
// The frontend never re-derives narrow-vs-widening itself — the mocked Outcome.status the BFF
// returns is what drives the UI, exactly as the real backend rule engine does (tested separately
// at rung 1/3/4). Route mocks use a URL predicate (never the '**/api/**' glob — it would hijack
// Vite's /src/api/* modules). axe scans (R4) run at every settled state a test already asserts.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = { id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }

const EXISTING_LADDER_ROW = {
  group: 'ops-oncall',
  role: 'RESPONDER',
  engineId: 'eng1',
  tenantId: '*',
  source: 'file',
}

interface MockState {
  mappingStatus: number
  adds: unknown[]
  removes: unknown[]
  approves: number[]
  ladderGrants: (typeof EXISTING_LADDER_ROW)[]
  proposals: { id: number; group: string; summary: string; proposer: string; reason: string }[]
}

async function mockBff(page: Page, opts: { mappingStatus?: number } = {}): Promise<MockState> {
  const state: MockState = {
    mappingStatus: opts.mappingStatus ?? 200,
    adds: [],
    removes: [],
    approves: [],
    ladderGrants: [{ ...EXISTING_LADDER_ROW }],
    proposals: [],
  }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      const approveMatch = /^\/api\/admin\/access\/proposals\/(\d+)\/approve$/.exec(pathname)

      if (method === 'GET' && pathname === '/api/admin/access') {
        if (state.mappingStatus !== 200) {
          await route.fulfill({
            status: state.mappingStatus,
            json: { status: state.mappingStatus, title: 'Forbidden' },
          })
        } else {
          await route.fulfill({ json: { ladderGrants: state.ladderGrants, fleetGrants: [] } })
        }
      } else if (method === 'GET' && pathname === '/api/admin/access/proposals') {
        await route.fulfill({ json: state.proposals })
      } else if (method === 'POST' && pathname === '/api/admin/access/grants') {
        const body = route.request().postDataJSON() as { type?: string; group?: string }
        state.adds.push(body)
        if (body.type === 'fleet') {
          const id = 42
          state.proposals.push({
            id,
            group: body.group ?? '',
            summary: `grant fleet REGISTRY_ADMIN to ${body.group ?? ''}`,
            proposer: 'alice',
            reason: 'onboarding the new platform group',
          })
          await route.fulfill({
            json: {
              status: 'proposed',
              proposalId: id,
              eligibleApproverGroups: ['access-admins'],
              summary: `grant fleet REGISTRY_ADMIN to ${body.group ?? ''}`,
            },
          })
        } else {
          state.ladderGrants.push({
            group: body.group ?? '',
            role: 'VIEWER',
            engineId: '*',
            tenantId: '*',
            source: 'file',
          })
          await route.fulfill({
            json: { status: 'applied', summary: `granted VIEWER on * to ${body.group ?? ''}` },
          })
        }
      } else if (method === 'DELETE' && pathname === '/api/admin/access/grants') {
        const body = route.request().postDataJSON() as { group?: string }
        state.removes.push(body)
        state.ladderGrants = state.ladderGrants.filter((r) => r.group !== body.group)
        await route.fulfill({
          json: { status: 'applied', summary: `revoked the grant from ${body.group ?? ''}` },
        })
      } else if (method === 'POST' && approveMatch !== null) {
        const id = Number(approveMatch[1])
        state.approves.push(id)
        const proposal = state.proposals.find((p) => p.id === id)
        state.proposals = state.proposals.filter((p) => p.id !== id)
        await route.fulfill({
          json: { status: 'applied', summary: proposal?.summary ?? 'approved' },
        })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'ADMIN', accessAdmin: true, engineRoles: {} } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === '/api/triage') {
        await route.fulfill({
          json: {
            asOf: '2026-07-12T03:32:00Z',
            statusCounts: {},
            errorGroups: [],
            perEngine: { eng1: { ok: true } },
            engines: [ENGINE],
          },
        })
      } else if (
        pathname === '/api/recents' ||
        pathname === '/api/bulk' ||
        pathname === '/api/views'
      ) {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

test('a narrow ladder grant applies immediately and can be revoked', async ({ page }) => {
  const state = await mockBff(page)
  await page.goto('/admin/access')

  const mapping = page.getByRole('region', { name: 'Effective mapping' })
  await expect(mapping.getByRole('cell', { name: 'ops-oncall' })).toBeVisible()
  await scanA11y(page, 'access-admin page loaded with effective mapping')

  await page.getByLabel('Group').fill('finance-view')
  await page.getByLabel('Reason (≥10 chars)').fill('routine onboarding for finance read access')
  const addButton = page.getByRole('button', { name: 'Add grant' })
  await expect(addButton).toBeEnabled()
  await addButton.click()

  await expect.poll(() => state.adds.length).toBe(1)
  expect(state.adds[0]).toMatchObject({ type: 'ladder', group: 'finance-view' })
  await expect(page.getByRole('status')).toHaveText(/Applied: granted VIEWER on \* to finance-view/)
  await scanA11y(page, 'applied-grant banner shown')

  const newRow = page.getByRole('row', { name: /finance-view/ })
  await expect(newRow).toBeVisible()
  await newRow.getByRole('button', { name: 'Revoke' }).click()

  await expect.poll(() => state.removes.length).toBe(1)
  expect(state.removes[0]).toMatchObject({ group: 'finance-view' })
  await expect(page.getByRole('status')).toHaveText(/Applied: revoked the grant from finance-view/)
  await expect(page.getByRole('row', { name: /finance-view/ })).toHaveCount(0)
})

test('a widening (fleet) grant proposes for four-eyes; a second ACCESS_ADMIN approves it from the inbox', async ({
  page,
}) => {
  const state = await mockBff(page)
  await page.goto('/admin/access')

  await expect(page.getByRole('heading', { name: 'Pending proposals (four-eyes)' })).toBeVisible()
  await expect(page.getByText('No pending proposals.')).toBeVisible()

  await page.getByLabel('Type').selectOption('fleet')
  await page.getByLabel('Group').fill('platform-admins')
  await page.getByLabel('Reason (≥10 chars)').fill('new platform team needs registry admin')
  await page.getByRole('button', { name: 'Add grant' }).click()

  await expect.poll(() => state.adds.length).toBe(1)
  expect(state.adds[0]).toMatchObject({ type: 'fleet', group: 'platform-admins' })
  await expect(page.getByRole('status')).toHaveText(
    /Proposed — this widens access.*Eligible approver group\(s\): access-admins\./,
  )

  const proposalItem = page.getByRole('listitem').filter({ hasText: 'platform-admins' })
  await expect(proposalItem).toBeVisible()
  await expect(proposalItem).toContainText('proposed by alice')
  await scanA11y(page, 'pending four-eyes proposal in the inbox')

  await proposalItem.getByRole('button', { name: 'Approve' }).click()

  await expect.poll(() => state.approves.length).toBe(1)
  expect(state.approves[0]).toBe(42)
  await expect(page.getByRole('status')).toHaveText(/Applied: grant fleet REGISTRY_ADMIN/)
  await expect(page.getByText('No pending proposals.')).toBeVisible()
  await scanA11y(page, 'proposal approved and inbox emptied')
})

test('a caller without ACCESS_ADMIN sees the greyed reason, not a blank or crashed page', async ({
  page,
}) => {
  await mockBff(page, { mappingStatus: 403 })
  await page.goto('/admin/access')

  await expect(page.getByRole('heading', { name: 'Access administration' })).toBeVisible()
  await expect(page.getByText(/Requires the ACCESS_ADMIN grant/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add grant' })).toHaveCount(0)
  await scanA11y(page, 'ACCESS_ADMIN-gated 403 explainer')
})

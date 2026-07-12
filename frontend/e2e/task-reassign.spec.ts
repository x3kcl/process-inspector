// v1.x #6 smoke: the Tasks-tab reassign / return-to-team arc — row action → modal →
// (Show as cURL) → dispatch. Hermetic (every /api call fulfilled below). The invariants
// these tests protect: the reassign submit carries { taskId, assignee } to the WHITELISTED
// BFF verb endpoint (never the engine), and the "Show as cURL" affordance renders the
// SERVER-computed command verbatim rather than a client-assembled string.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = {
  id: 'eng1',
  name: 'Payments DEV',
  environment: 'dev',
  mode: 'FULL',
  reachable: true,
}
const INSTANCE = 'p-1'

const TASK = {
  id: 'task-9',
  name: 'Manual review',
  taskDefinitionKey: 'review',
  state: 'ACTIVE',
  assignee: 'kermit',
  createTime: '2026-07-07T03:00:00Z',
}

const CURL =
  "curl -X POST 'http://localhost/api/instances/eng1/p-1/actions/reassign-task'" +
  " -H 'Content-Type: application/json' -H 'Authorization: Basic <your-credentials>'" +
  ' -d \'{"taskId":"task-9","assignee":"gonzo"}\''

interface MockState {
  submits: unknown[]
  curlRequests: unknown[]
}

// Predicate route (never the '**/api/**' glob, which would also hijack Vite's /src/api/*
// module loads and brick the dev server — TEST-STRATEGY §9).
async function mockBff(page: Page, opts: { role: string }): Promise<MockState> {
  const state: MockState = { submits: [], curlRequests: [] }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && pathname === '/api/instances/eng1/p-1/actions/reassign-task/curl') {
        state.curlRequests.push(route.request().postDataJSON())
        await route.fulfill({ json: { curl: CURL } })
      } else if (
        method === 'POST' &&
        pathname === '/api/instances/eng1/p-1/actions/reassign-task'
      ) {
        state.submits.push(route.request().postDataJSON())
        await route.fulfill({
          json: {
            auditId: 'a1',
            correlationId: 'c1',
            outcome: 'ok',
            deltaStatement: "Task task-9 reassigned to 'gonzo'.",
          },
        })
      } else if (pathname === '/api/me') {
        await route.fulfill({ json: { role: opts.role, engineRoles: { eng1: opts.role } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === '/api/instances/eng1/p-1/tasks') {
        await route.fulfill({ json: { tasks: [TASK], total: 1, truncated: false } })
      } else if (pathname === '/api/bulk') {
        // The Shell's operations drawer expects an array; a bare {} would crash it.
        await route.fulfill({ json: [] })
      } else {
        // vitals, diagram, jobs, etc. — enough to render the shell without crashing.
        await route.fulfill({ json: {} })
      }
    },
  )
  return state
}

test('reassign: row action → modal → dispatch carries { taskId, assignee } to the BFF verb', async ({
  page,
}) => {
  const state = await mockBff(page, { role: 'OPERATOR' })
  await page.goto(`/inspect/eng1/${INSTANCE}?tab=tasks`)

  const row = page.getByRole('row', { name: /Manual review/ })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Reassign', exact: true }).click()

  const modal = page.getByRole('dialog', { name: /Reassign/ })
  await expect(modal).toBeVisible()
  await expect(modal.getByText('kermit')).toBeVisible()
  await scanA11y(page, 'reassign task modal open')

  // Confirm is disabled until a target id is entered.
  const confirm = modal.getByRole('button', { name: /^Reassign/ })
  await expect(confirm).toBeDisabled()
  await modal.getByLabel(/Target user id/).fill('gonzo')
  await expect(confirm).toBeEnabled()

  // Show as cURL renders the SERVER string verbatim (no client re-assembly).
  await modal.getByRole('button', { name: 'Show as cURL' }).click()
  await expect(modal.getByText(/actions\/reassign-task'/)).toBeVisible()
  await expect(modal.getByText(/Authorization: Basic <your-credentials>/)).toBeVisible()
  expect(state.curlRequests.length).toBeGreaterThan(0)
  await scanA11y(page, 'reassign modal with cURL command shown')

  await confirm.click()

  // THE invariant: the body hits the whitelisted BFF verb with taskId + assignee.
  expect(state.submits).toHaveLength(1)
  expect(state.submits[0]).toMatchObject({ taskId: 'task-9', assignee: 'gonzo' })
  await expect(modal).toHaveCount(0)
})

test('return to team is greyed for a VIEWER, with the gate named', async ({ page }) => {
  const state = await mockBff(page, { role: 'VIEWER' })
  await page.goto(`/inspect/eng1/${INSTANCE}?tab=tasks`)

  const row = page.getByRole('row', { name: /Manual review/ })
  const reassign = row.getByRole('button', { name: 'Reassign', exact: true })
  await expect(reassign).toBeDisabled()
  await expect(reassign).toHaveAttribute('title', /OPERATOR/)
  expect(state.submits).toEqual([])
  await scanA11y(page, 'tasks tab with reassign gated for VIEWER')
})

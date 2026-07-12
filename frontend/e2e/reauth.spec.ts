// The dangerous-set re-auth interstitial (IDP-SECURITY.md §5, R-SAFE-07): a stale OIDC session
// is challenged at verb INTENT — pre-emptively via the /api/me `reauth` hint at modal open, or
// reactively via the BFF's 401 `reauth-required` answer — and the "Re-authenticate now" button
// checkpoints the route before the full-page OIDC round-trip, which the Shell restores on the
// post-login boot. Hermetic (predicate route, never the '**/api/**' glob — TEST-STRATEGY §9).
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

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
  exceptionMessage: 'java.lang.RuntimeException: gateway timeout',
  retries: 0,
  processInstanceId: 'p-1',
}

const REAUTH_PROBLEM = {
  status: 401,
  title: 'Re-authentication required — nothing happened',
  detail:
    'Re-authentication required: this action needs a sign-in newer than 15 minutes. Re-authenticate and try again — nothing happened.',
  code: 'reauth-required',
  outcome: 'refused',
  freshnessWindowSeconds: 900,
}

/** reauth: the /api/me freshness hint under test; challengeAction: the POST answers the 401. */
async function mockBff(
  page: Page,
  opts: { reauth?: Record<string, unknown>; challengeAction?: boolean; sessionExpiresAt?: string },
): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({
          json: {
            role: 'ADMIN',
            engineRoles: { eng1: 'ADMIN' },
            reauth: opts.reauth,
            sessionExpiresAt: opts.sessionExpiresAt,
          },
        })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === '/api/instances/eng1/p-1/jobs') {
        await route.fulfill({
          json: { executable: [], timer: [], suspended: [], deadLetter: [DEAD_LETTER_JOB] },
        })
      } else if (pathname === '/api/instances/eng1/p-1/actions/delete-deadletter') {
        if (opts.challengeAction === true) {
          await route.fulfill({
            status: 401,
            headers: { 'X-Reauth-Required': 'true' },
            json: REAUTH_PROBLEM,
          })
        } else {
          await route.fulfill({ json: { outcome: 'ok', httpStatus: 200, auditId: 'audit-1' } })
        }
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

/** Open the tier-3 delete-deadletter modal (the canonical dangerous verb surface). */
async function openDeleteModal(page: Page) {
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')
  const lane = page.locator('details.lane-deadLetter')
  await expect(lane).toBeVisible()
  await lane.getByRole('button', { name: 'Delete', exact: true }).click()
  const modal = page.getByRole('dialog')
  await expect(modal).toBeVisible()
  return modal
}

test('a stale hint pre-empts at modal open: interstitial shown, confirm disabled BEFORE typing', async ({
  page,
}) => {
  await mockBff(page, { reauth: { required: true, windowSeconds: 900 } })
  const modal = await openDeleteModal(page)

  // The interstitial replaces the error-banner slot before the operator types anything.
  await expect(modal.getByText(/sign-in newer than 15 minutes/)).toBeVisible()
  const reauthButton = modal.getByRole('button', { name: 'Re-authenticate now' })
  await expect(reauthButton).toBeVisible()
  await scanA11y(page, 'pre-emptive reauth interstitial shown before typing')

  // Even a perfect reason leaves the confirm disabled — freshness first, token later.
  await modal.getByRole('textbox').first().fill('vendor confirmed the charge already settled')
  await expect(modal.getByRole('button', { name: /Delete dead-letter job/ })).toBeDisabled()
})

test('the re-auth button checkpoints the route and navigates to the oidc re-auth entry', async ({
  page,
}) => {
  await mockBff(page, { reauth: { required: true, windowSeconds: 900 } })
  // The oidc entry is a BFF route (not /api) — stub it so the top-level navigation lands.
  await page.route(
    (url) => url.pathname === '/oauth2/authorization/oidc',
    async (route) => {
      await route.fulfill({ contentType: 'text/html', body: '<title>idp-stub</title>' })
    },
  )
  const modal = await openDeleteModal(page)
  await scanA11y(page, 'delete confirm modal open')
  await modal.getByRole('button', { name: 'Re-authenticate now' }).click()

  await page.waitForURL(/\/oauth2\/authorization\/oidc\?reauth=true/)
  // The pre-redirect route survived into sessionStorage for the post-login restore.
  const checkpoint = await page.evaluate(() => sessionStorage.getItem('inspector.reauth.resume'))
  expect(checkpoint).not.toBeNull()
  expect(JSON.parse(checkpoint ?? '{}')).toMatchObject({
    href: '/inspect/eng1/p-1?tab=errors-jobs',
  })
})

test('a fresh hint but a 401 challenge on submit surfaces the interstitial reactively', async ({
  page,
}) => {
  // The hint says fresh (e.g. the session went stale between me-fetch and click) — the BFF
  // challenge is the backstop, and the modal flips to the interstitial instead of a dead banner.
  await mockBff(page, {
    reauth: { required: false, windowSeconds: 900 },
    challengeAction: true,
  })
  const modal = await openDeleteModal(page)
  const confirm = modal.getByRole('button', { name: /Delete dead-letter job/ })
  await modal.getByRole('textbox').first().fill('vendor confirmed the charge already settled')
  await expect(confirm).toBeEnabled()
  await scanA11y(page, 'delete confirm modal with reason filled, confirm enabled')
  await confirm.click()

  await expect(modal.getByRole('button', { name: 'Re-authenticate now' })).toBeVisible()
  await expect(confirm).toBeDisabled()
  await scanA11y(page, 'reactive reauth interstitial after 401 challenge')
})

test('the post-login boot restores the checkpointed route (single-shot)', async ({ page }) => {
  await mockBff(page, { reauth: { required: false } })
  // Seed the checkpoint the way checkpointAndReauth() writes it, then boot on '/' — the
  // default post-login landing.
  await page.addInitScript(() => {
    sessionStorage.setItem(
      'inspector.reauth.resume',
      JSON.stringify({ href: '/inspect/eng1/p-1?tab=errors-jobs', ts: Date.now() }),
    )
  })
  await page.goto('/')

  await page.waitForURL(/\/inspect\/eng1\/p-1\?tab=errors-jobs/)
  await scanA11y(page, 'post-login boot restored to the checkpointed errors-jobs tab')
  // Single-shot: the checkpoint is consumed.
  expect(await page.evaluate(() => sessionStorage.getItem('inspector.reauth.resume'))).toBeNull()
})

test('warn-before-guillotine: an OIDC session near the absolute cap gets the countdown + CTA', async ({
  page,
}) => {
  const soon = new Date(Date.now() + 10 * 60_000).toISOString()
  const fresh = new Date(Date.now() + 5 * 60_000).toISOString()
  await mockBff(page, {
    reauth: { required: false, freshUntil: fresh, windowSeconds: 900 },
    sessionExpiresAt: soon,
  })
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  const banner = page.locator('.session-expiry-banner')
  await expect(banner).toBeVisible()
  await expect(banner).toContainText(/Session expires in (9|10) min/)
  await expect(banner.getByRole('button', { name: 'Re-authenticate now' })).toBeVisible()
  await scanA11y(page, 'session-expiry banner with re-auth CTA')
})

test('warn-before-guillotine: a break-glass-shaped session counts down WITHOUT a re-auth CTA', async ({
  page,
}) => {
  // Break-glass (and dev basic) answer {required:false, freshUntil:null} — no IdP to bounce
  // through, so the banner warns but offers no button.
  const soon = new Date(Date.now() + 10 * 60_000).toISOString()
  await mockBff(page, {
    reauth: { required: false, freshUntil: null, windowSeconds: 900 },
    sessionExpiresAt: soon,
  })
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  const banner = page.locator('.session-expiry-banner')
  await expect(banner).toBeVisible()
  await scanA11y(page, 'session-expiry banner without a re-auth CTA (break-glass)')
  await expect(banner.getByRole('button')).toHaveCount(0)
})

test('warn-before-guillotine: silent while the cap is far away', async ({ page }) => {
  const farAway = new Date(Date.now() + 20 * 3_600_000).toISOString()
  await mockBff(page, { reauth: { required: false }, sessionExpiresAt: farAway })
  await page.goto('/inspect/eng1/p-1?tab=errors-jobs')

  await expect(page.locator('details.lane-deadLetter')).toBeVisible() // page rendered
  await scanA11y(page, 'instance page with no session-expiry banner, cap far away')
  await expect(page.locator('.session-expiry-banner')).toHaveCount(0)
})

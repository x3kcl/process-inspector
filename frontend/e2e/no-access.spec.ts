// #758: a signed-in user who holds no role on any engine (an unmapped OIDC user) lands on the
// "no access granted yet" state — a real heading, the ask-an-admin text, Sign out reachable —
// and the SPA calls no gated route to get there (each would answer 403 no-engine-role). Axe-clean
// in both themes. Hermetic (mocked BFF).
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const NO_ROLE = {
  username: 'pat',
  role: null,
  engineRoles: {},
  registryAdmin: false,
  accessAdmin: false,
  breakGlass: false,
}

async function mockNoRoleBff(page: Page): Promise<string[]> {
  const gated: string[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: NO_ROLE })
      } else if (pathname === '/api/meta') {
        await route.fulfill({ json: { ticketUrlTemplate: null } })
      } else {
        gated.push(pathname)
        await route.fulfill({
          status: 403,
          json: { status: 403, title: 'No access granted', code: 'no-engine-role' },
        })
      }
    },
  )
  return gated
}

for (const theme of ['light', 'dark'] as const) {
  test(`a user with no role sees the no-access state, calls no gated route (${theme})`, async ({
    page,
  }) => {
    await page.addInitScript((t) => {
      localStorage.setItem('inspector.theme', t)
    }, theme)
    const gated = await mockNoRoleBff(page)

    await page.goto('/')

    await expect(page.getByRole('heading', { name: 'No access granted yet' })).toBeVisible()
    await expect(page.getByText(/ask an Inspector admin/i)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
    await scanA11y(page, `no-access state (${theme})`)
    expect(gated).toEqual([])
  })
}

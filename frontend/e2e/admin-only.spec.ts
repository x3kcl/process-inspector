import { expect, test } from '@playwright/test'
import { scanA11y } from './a11y'

const ADMIN_ONLY = {
  username: 'ada',
  role: null,
  engineRoles: {},
  registryAdmin: true,
  accessAdmin: false,
  breakGlass: false,
}

for (const theme of ['light', 'dark'] as const) {
  test(`an admin-only user lands on the registry page, calls no gated route (${theme})`, async ({
    page,
  }) => {
    await page.addInitScript((t) => {
      localStorage.setItem('inspector.theme', t)
    }, theme)
    const gated: string[] = []
    await page.route(
      (url) => url.pathname.startsWith('/api/'),
      async (route) => {
        const { pathname } = new URL(route.request().url())
        if (pathname === '/api/me') await route.fulfill({ json: ADMIN_ONLY })
        else if (pathname === '/api/meta')
          await route.fulfill({ json: { ticketUrlTemplate: null } })
        else if (pathname.startsWith('/api/admin/')) await route.fulfill({ json: [] })
        else {
          gated.push(pathname)
          await route.fulfill({
            status: 403,
            json: { status: 403, title: 'No access granted', code: 'no-engine-role' },
          })
        }
      },
    )

    await page.goto('/')

    await expect(page).toHaveURL(/\/admin\/engines$/)
    await expect(page.getByText(/administrative access but no engine role/i)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
    await scanA11y(page, `admin-only shell (${theme})`)
    expect(gated).toEqual([])
  })
}

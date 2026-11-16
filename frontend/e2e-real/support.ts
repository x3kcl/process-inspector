import { expect } from '@playwright/test'
import type { Page, Request } from '@playwright/test'

/** The run-unique business-key prefix global-setup.ts tagged every fixture with. */
export function runPrefix(): string {
  const run = process.env.E2E_RUN
  if (run === undefined) throw new Error('E2E_RUN unset — global-setup.ts did not run')
  return run
}

const NETWORK_CHANGED = 'net::ERR_NETWORK_CHANGED'

/**
 * A full-page load that survives Chromium's ERR_NETWORK_CHANGED (#702). The CI slots run
 * host-networked beside other jobs' docker networks; a bridge/veth appearing mid-load makes
 * Chromium abort every in-flight request, and a module script that fails to load leaves the
 * SPA blank for good (nightly #174: Shell.tsx, TriagePage.tsx, … aborted → no sign-in form).
 * Only that browser-declared abort re-loads, at most twice, and it says so in the log; any
 * other load failure stays a failure.
 */
export async function open(page: Page, url: string): Promise<void> {
  for (let attempt = 1; ; attempt++) {
    let aborted = false
    const onFailed = (request: Request) => {
      if (request.failure()?.errorText === NETWORK_CHANGED) aborted = true
    }
    page.on('requestfailed', onFailed)
    try {
      await page.goto(url)
    } catch (error) {
      if (!String(error).includes(NETWORK_CHANGED) || attempt === 3) throw error
      aborted = true
    } finally {
      page.off('requestfailed', onFailed)
    }
    if (!aborted) return
    if (attempt === 3) throw new Error(`${url}: ${NETWORK_CHANGED} on 3 loads in a row`)
    console.warn(
      `e2e-real: ${NETWORK_CHANGED} while loading ${url} — load ${String(attempt + 1)}/3`,
    )
  }
}

/** Signs in through the real dev-chain form (SecurityConfig ladder users, password `dev`). */
export async function signIn(page: Page, user: string): Promise<void> {
  await open(page, '/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('dev')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('group', { name: 'Signed-in identity' })).toContainText(user)
}

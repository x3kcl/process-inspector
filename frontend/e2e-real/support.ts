import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'

/** The run-unique business-key prefix global-setup.ts tagged every fixture with. */
export function runPrefix(): string {
  const run = process.env.E2E_RUN
  if (run === undefined) throw new Error('E2E_RUN unset — global-setup.ts did not run')
  return run
}

/** Signs in through the real dev-chain form (SecurityConfig ladder users, password `dev`). */
export async function signIn(page: Page, user: string): Promise<void> {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('dev')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('group', { name: 'Signed-in identity' })).toContainText(user)
}

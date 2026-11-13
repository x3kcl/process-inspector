// Regression (#610): on the real stack every page logged React's "Cannot update a component
// (`Shell`) while rendering a different component (`IdentityStrip`)". Hermetic mocks never hit
// it — it needs the real sign-in flip (401 cache -> authenticated) — so it is asserted here.
// Dev-mode Vite serves React's development build, which is where this warning exists.
import { expect, test } from '@playwright/test'
import { scanA11y } from '../e2e/a11y'
import { signIn } from './support'

test('sign-in and navigation raise no React cross-component update warning', async ({ page }) => {
  const warnings: string[] = []
  page.on('console', (msg) => {
    const text = msg.text()
    if (msg.type() === 'error' || msg.type() === 'warning') {
      if (/Cannot update a component/.test(text)) warnings.push(text)
    }
  })
  await signIn(page, 'viewer')
  await page.goto('/audit')
  await expect(page.getByRole('group', { name: 'Signed-in identity' })).toBeVisible()
  await page.goto('/')
  await expect(page.getByRole('group', { name: 'Engine health' })).toBeVisible()
  await scanA11y(page, 'triage landing after sign-in')
  expect(warnings).toEqual([])
})

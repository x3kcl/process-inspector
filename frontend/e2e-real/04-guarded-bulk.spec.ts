// Arc (SPEC §7 bulk rails on FIX-ACME-06): select-all-matching on this run's acmeApiOutage
// dead-letters only (business-key filter), the reason-gated Suspend, the operations drawer
// reporting per-item completion, then the engines' own state read back through search.
import { expect, test } from '@playwright/test'
import { scanA11y } from '../e2e/a11y'
import { runPrefix, signIn } from './support'

test('guarded bulk suspend on the FIX-ACME-06 fixtures, verified through search', async ({
  page,
}) => {
  const filter = `businessKeyLike=${runPrefix()}-ACME`
  await signIn(page, 'operator')
  await page.goto(`/search?status=FAILED&definitionKey=acmeApiOutage&${filter}`)

  await page.getByRole('button', { name: 'Select all ~2 matching filter…' }).click()
  const bar = page.getByRole('toolbar', { name: 'bulk actions' })
  await expect(bar.getByText(/resolved\s+server-side at execution/)).toBeVisible()
  await scanA11y(page, 'bulk toolbar scoped to the ACME fixtures')
  await bar.getByRole('button', { name: 'Suspend' }).click()

  const modal = page.getByRole('dialog', { name: /every instance matching/ })
  await expect(modal.getByText(`business key ~ ${runPrefix()}-ACME`)).toBeVisible()
  const confirm = modal.getByRole('button', { name: 'Suspend — all matching the filter' })
  // The guard: no dispatch without a ≥10-char reason.
  await expect(confirm).toBeDisabled()
  await scanA11y(page, 'bulk suspend modal')
  await modal.getByLabel(/^Why are you doing this/).fill(`e2e-real ${runPrefix()}: bulk smoke`)
  await expect(confirm).toBeEnabled()
  await confirm.click()

  const drawer = page.getByRole('complementary', { name: 'Operations drawer' })
  // Newest job first; older runs' jobs may sit below it on a long-lived stack.
  await expect(
    drawer.getByRole('button', { name: /suspend FAILED · acmeApiOutage/ }).first(),
  ).toContainText('COMPLETED', { timeout: 60_000 })
  await scanA11y(page, 'operations drawer after the bulk job')

  await page.goto(`/search?status=SUSPENDED&${filter}`)
  await expect(page.getByText(/^2 instances ·/)).toBeVisible()
  await scanA11y(page, 'suspended fixtures in the grid')
})

// Arc: "is the fleet healthy?" — the header health strip for any signed-in user, then the
// REGISTRY_ADMIN engines page and the per-engine system page. Read-only on purpose: the
// registry's "Test connection" re-probes an engine and moves its lifecycle to `probed`, which
// takes a live engine out of the active set — not something a smoke may do to a shared stack.
import { expect, test } from '@playwright/test'
import { scanA11y } from '../e2e/a11y'
import { signIn } from './support'

test('health strip shows both real engines with live job lanes', async ({ page }) => {
  await signIn(page, 'viewer')
  const strip = page.getByRole('group', { name: 'Engine health' })
  for (const name of ['Engine A (demo)', 'Engine B (demo)']) {
    await expect(strip.getByText(name, { exact: true })).toBeVisible()
  }
  // Version comes from the engine's own /management/engine — proof the BFF reached it.
  await expect(strip.getByText(/^v6\.8\.\d+$/)).toHaveCount(2)
  await expect(strip.getByTitle('dead-letter jobs')).toHaveCount(2)
  for (const lane of await strip.getByTitle('dead-letter jobs').all()) {
    await expect(lane).toHaveText(/^DLQ \d+$/)
  }
  await scanA11y(page, 'triage landing with the health strip')
})

test('registry admin sees both engines active and reachable, then their system page', async ({
  page,
}) => {
  await signIn(page, 'registry-admin')
  await page.getByRole('link', { name: 'Engines', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Engine registry' })).toBeVisible()
  for (const id of ['engine-a', 'engine-b']) {
    const row = page.getByRole('row').filter({ has: page.getByText(id, { exact: true }) })
    await expect(row).toContainText('Active — reachable')
    await expect(row).toContainText('✓ present')
  }
  await scanA11y(page, 'admin engines registry')

  await page.goto('/')
  await page
    .getByRole('group', { name: 'Engine health' })
    .getByRole('link', { name: 'system' })
    .first()
    .click()
  await expect(page.getByRole('heading', { name: /^System info engine-/ })).toBeVisible()
  await expect(page.getByText(/^Version: 6\.8\.\d+$/)).toBeVisible()
  await scanA11y(page, 'engine system info')
})

// Arc (GOAL-CATALOG find → diagnose → fix → verify, R-SEM-03 + the retry rails): triage
// landing → the ArithmeticException error-class card → the live grid → inspect this run's
// dead-lettered fixture → fix the bad variable → retry the job → the engine completes it → both
// actions in the audit trail. TS-E2E-01 against real engines (copy-for-ticket stays hermetic).
import { expect, test } from '@playwright/test'
import { scanA11y } from '../e2e/a11y'
import { runPrefix, signIn } from './support'

test('triage → error group → inspect → fix variable → retry job → COMPLETED → audited', async ({
  page,
}) => {
  const businessKey = `${runPrefix()}-RETRY`
  await signIn(page, 'operator')

  const card = page
    .getByRole('region', { name: 'Failures by error class' })
    .getByRole('article')
    .filter({ has: page.getByText('ArithmeticException', { exact: true }) })
  await expect(card).toBeVisible()
  await scanA11y(page, 'triage landing with error classes')
  await card.getByRole('link', { name: /^\d+ instances?$/ }).click()

  // The count opens a LIVE search by signature — this run's fixture is in it even when the
  // card's cached (~20s) count predates it.
  await expect(page).toHaveURL(/signature=/)
  const row = page.getByRole('row').filter({ hasText: businessKey })
  await expect(row).toContainText('FAILED')
  await scanA11y(page, 'error-class grid')
  await row.getByRole('link', { name: 'Open →' }).click()

  await expect(page.getByRole('heading', { name: 'Instance detail' })).toBeVisible()
  const status = page.getByRole('button', { name: /— explain this status$/ }).first()
  await expect(status).toHaveText('FAILED')
  await expect(page.getByRole('alert').getByText(/\$\{amount % divisor\}/)).toBeVisible()
  await scanA11y(page, 'instance detail of the dead-lettered fixture')

  // Fix: divisor 0 → 1 through the guarded variable editor.
  await page.getByRole('button', { name: 'edit divisor' }).click()
  await page.getByLabel('new numeric value').fill('1')
  await page.getByRole('button', { name: 'Review change…' }).click()
  const verify = page.getByRole('dialog', { name: 'Verify the change' })
  await expect(verify).toBeVisible()
  await scanA11y(page, 'variable change verification dialog')
  await verify.getByLabel(/^Reason/).fill(`e2e-real ${businessKey}: divisor fix`)
  await verify.getByRole('button', { name: /^Change divisor from 0 to 1/ }).click()
  await expect(verify).toHaveCount(0)
  await expect(
    page.getByRole('row').filter({ has: page.getByText('divisor', { exact: true }) }),
  ).toContainText('1')

  // Retry: the dead-letter job back to executable, once.
  await page.getByRole('tab', { name: 'Errors & Jobs' }).click()
  const deadLetter = page.getByRole('group').filter({ hasText: /^Dead-letter \(1\)/ })
  await scanA11y(page, 'errors and jobs tab with the dead-letter job')
  await deadLetter.getByRole('button', { name: 'Retry job' }).click()

  // Verify against the REAL engine through the BFF: the async executor runs the job and the
  // instance ends. Reload is a read; the mutation above happened exactly once.
  await expect(async () => {
    await page.reload()
    await expect(page.getByRole('button', { name: /— explain this status$/ }).first()).toHaveText(
      'COMPLETED',
      { timeout: 5_000 },
    )
  }).toPass({ timeout: 90_000, intervals: [2_000] })
  await scanA11y(page, 'instance detail after the retry completed it')

  // TS-E2E-01 tail: both corrective actions are in this instance's audit trail.
  await page.getByRole('tab', { name: 'Audit & Notes' }).click()
  const audit = page.getByRole('tabpanel', { name: 'Audit & Notes' })
  await expect(audit.getByRole('row').filter({ hasText: 'retry-job' })).toContainText('operator')
  await expect(audit.getByRole('row').filter({ hasText: 'edit-variable' })).toContainText(
    `e2e-real ${businessKey}: divisor fix`,
  )
  await scanA11y(page, 'audit trail with both corrective actions')
})

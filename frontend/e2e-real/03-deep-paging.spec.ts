// Arc (R-NFR-08 deep-paging envelope, K-way merge): a filter matching more rows than one
// page on BOTH engines, walked with "Load more" until the engines' reported total is reached
// — no row dropped or duplicated across the cross-engine cursor pages.
import { expect, test } from '@playwright/test'
import { scanA11y } from '../e2e/a11y'
import { PAGE_FIXTURES } from './global-setup'
import { runPrefix, signIn } from './support'

test('search with deep paging walks every cursor page across both engines', async ({ page }) => {
  await signIn(page, 'viewer')
  // pageSize is per engine: 5 + 5 on the first page, the rest only through Load more.
  await page.goto(`/search?status=ACTIVE&businessKeyLike=${runPrefix()}-PAGE&pageSize=5`)

  await expect(page.getByText(/^10 instances loaded so far/)).toBeVisible()
  await expect(
    page.getByRole('button', { name: `Select all ~${String(PAGE_FIXTURES)} matching filter…` }),
  ).toBeVisible()
  const loadMore = page.getByRole('region', { name: 'Load more results' })
  await scanA11y(page, 'first page of a deep result set')

  // Each Load more appends the next K-way-merged cursor page; walk until the engines run dry.
  const loaded = page.getByText(/^\d+ instances (loaded so far )?·/)
  const count = async () => Number.parseInt((await loaded.textContent()) ?? '0', 10)
  for (let pages = 0; pages < 10; pages++) {
    const button = loadMore.getByRole('button', { name: 'Load more' })
    if ((await button.count()) === 0) break
    const before = await count()
    await button.click()
    await expect.poll(count, { timeout: 30_000 }).toBeGreaterThan(before)
  }

  await expect(page.getByText(new RegExp(`^${String(PAGE_FIXTURES)} instances ·`))).toBeVisible()
  await expect(page.getByText(new RegExp(`^${String(PAGE_FIXTURES)} rows`))).toBeVisible()
  await expect(loadMore.getByRole('button', { name: 'Load more' })).toHaveCount(0)
  await expect(loadMore.getByText(/^Loaded more as of/)).toBeVisible()
  await scanA11y(page, 'deep result set fully loaded')
})

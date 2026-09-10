import { test, expect } from '@playwright/test'

/**
 * PLAN.md Phase 8: "a forced payment failure shows the compensation steps on the timeline and
 * the order ends CANCELLED." Requires payment-service running with SPRING_PROFILES_ACTIVE
 * including `chaos` (docker-compose.yml's local-dev default, per ARCHITECTURE.md §10.4) so the
 * "make this one fail" control's POST /test/failure-mode call actually takes effect.
 */
test('a forced payment failure shows compensation on the timeline and ends CANCELLED', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('Username').fill('ops')
  await page.getByLabel('Password').fill('ops_local_dev_only')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL('/')

  await page.getByRole('button', { name: 'Place order' }).click()
  const dialog = page.getByRole('dialog')
  const skuSelect = dialog.getByLabel('SKU')
  await expect(skuSelect.locator('option')).not.toHaveCount(0)
  await dialog.getByLabel('Quantity').fill('1')
  await dialog.getByLabel(/Make this one fail/).check()
  await dialog.getByRole('button', { name: 'Place order' }).click()

  const resultText = dialog.getByText(/Order .* placed\./)
  await expect(resultText).toBeVisible({ timeout: 10_000 })
  const orderId = (await resultText.textContent())?.match(/Order (\S+) placed\./)?.[1]
  expect(orderId).toBeTruthy()

  await dialog.getByRole('button', { name: 'Close' }).click()

  const cancelledColumn = page.getByTestId('column-CANCELLED')
  await expect(cancelledColumn).toContainText(orderId!.slice(0, 8), { timeout: 20_000 })

  await cancelledColumn.getByText(orderId!.slice(0, 8)).click()
  await expect(page.getByText('CANCELLED')).toBeVisible()

  const timeline = page.locator('text=Saga timeline').locator('..')
  await expect(timeline).toContainText('RELEASE_INVENTORY')
  await expect(timeline).toContainText('COMPENSATION')

  // Leave the gateway harmless for whatever runs next against this shared stack.
  await page.request.post('/test/failure-mode', { data: { mode: 'DECLINE', probability: 0 } })
})

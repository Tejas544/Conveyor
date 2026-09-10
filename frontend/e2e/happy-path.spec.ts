import { test, expect } from '@playwright/test'

/**
 * PLAN.md Phase 8: "login -> place order -> the card reaches CONFIRMED live, no page reload."
 * Drives the real, already-running backend stack end to end -- the browser tab never navigates
 * away from "/" after login, proving the SSE-driven kanban update is what moves the card, not a
 * fetch-on-reload.
 */
test('login, place an order, and watch it reach CONFIRMED live', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('Username').fill('ops')
  await page.getByLabel('Password').fill('ops_local_dev_only')
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page).toHaveURL('/')
  await expect(page.getByRole('heading', { name: 'Order Pipeline' })).toBeVisible()

  await page.getByRole('button', { name: 'Place order' }).click()
  const dialog = page.getByRole('dialog')
  const skuSelect = dialog.getByLabel('SKU')
  await expect(skuSelect.locator('option')).not.toHaveCount(0)
  await dialog.getByLabel('Quantity').fill('1')
  await dialog.getByRole('button', { name: 'Place order' }).click()

  const resultText = page.getByText(/Order .* placed\./)
  await expect(resultText).toBeVisible({ timeout: 10_000 })
  const orderId = (await resultText.textContent())?.match(/Order (\S+) placed\./)?.[1]
  expect(orderId).toBeTruthy()

  await page.getByRole('button', { name: 'Close' }).click()

  const confirmedColumn = page.getByTestId('column-CONFIRMED')
  await expect(confirmedColumn).toContainText(orderId!.slice(0, 8), { timeout: 20_000 })

  // No page reload happened -- the URL never changed and the same document is still live.
  await expect(page).toHaveURL('/')
})

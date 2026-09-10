import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

/** PLAN.md Phase 8: "axe reports no critical violations." */
test.describe('accessibility', () => {
  test('login page has no critical axe violations', async ({ page }) => {
    await page.goto('/login')
    const results = await new AxeBuilder({ page }).analyze()
    const critical = results.violations.filter((v) => v.impact === 'critical')
    expect(critical, JSON.stringify(critical, null, 2)).toEqual([])
  })

  test('kanban dashboard has no critical axe violations', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('Username').fill('ops')
    await page.getByLabel('Password').fill('ops_local_dev_only')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL('/')
    await page.getByRole('heading', { name: 'Order Pipeline' }).waitFor()

    const results = await new AxeBuilder({ page }).analyze()
    const critical = results.violations.filter((v) => v.impact === 'critical')
    expect(critical, JSON.stringify(critical, null, 2)).toEqual([])
  })

  test('kanban and login are keyboard-navigable to their primary actions', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('Username').focus()
    await page.keyboard.type('ops')
    await page.keyboard.press('Tab')
    await page.keyboard.type('ops_local_dev_only')
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL('/')

    // Tab from the top of the document to the "Place order" trigger and activate it with the keyboard.
    await page.keyboard.press('Tab')
    let reachedPlaceOrder = false
    for (let i = 0; i < 10; i++) {
      const active = await page.evaluate(() => document.activeElement?.textContent)
      if (active === 'Place order') {
        reachedPlaceOrder = true
        break
      }
      await page.keyboard.press('Tab')
    }
    expect(reachedPlaceOrder).toBe(true)
    await page.keyboard.press('Enter')
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })
})

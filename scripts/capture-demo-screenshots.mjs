// One-off script for Phase 16's docs/DEMO.md artifact set (same pattern as Phase 9's
// scripts/capture-trace-screenshot.mjs). Not part of the app; not wired into any build step.
// Requires: the frontend dev server running (npm run dev, port 5173) against a live,
// seeded docker-compose stack with payment-service's chaos profile active.
// Usage: node scripts/capture-demo-screenshots.mjs <outputDir>
import { chromium } from "playwright";

const [outDir] = process.argv.slice(2);
if (!outDir) {
  console.error("Usage: node capture-demo-screenshots.mjs <outputDir>");
  process.exit(1);
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });

await page.goto("http://localhost:5173");
await page.waitForSelector("text=Conveyor Ops");
await page.screenshot({ path: `${outDir}/1-login.png` });

await page.getByLabel("Username").fill("admin");
await page.getByLabel("Password").fill("admin_local_dev_only");
await page.getByRole("button", { name: "Sign in" }).click();
await page.waitForSelector("text=Order Pipeline");
await page.waitForTimeout(1000);
await page.screenshot({ path: `${outDir}/2-kanban-board.png` });

// A normal order, watched live moving to Confirmed.
await page.getByRole("button", { name: "Place order" }).click();
await page.getByRole("button", { name: "Place order", exact: true }).click();
await page.waitForTimeout(3000);
await page.keyboard.press("Escape");
await page.screenshot({ path: `${outDir}/3-happy-path-confirmed.png` });

// A forced payment failure, watched live compensating.
await page.getByRole("button", { name: "Place order" }).click();
await page.getByRole("checkbox").check();
await page.getByRole("button", { name: "Place order", exact: true }).click();
await page.waitForTimeout(500);
const orderLink = await page.locator("a[href^='/orders/']").first().getAttribute("href");
await page.keyboard.press("Escape");
await page.goto(`http://localhost:5173${orderLink}`);
await page.waitForSelector("text=RELEASE_INVENTORY", { timeout: 15000 });
await page.waitForTimeout(1000);
await page.screenshot({ path: `${outDir}/4-forced-failure-compensation.png`, fullPage: true });

await browser.close();
console.log("Saved 4 screenshots to", outDir);

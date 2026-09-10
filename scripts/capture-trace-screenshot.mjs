// One-off script for the Phase 9 exit criterion ("a screenshot of one order's full
// distributed trace, in docs/"). Not part of the app; not wired into any build step.
// Usage: node scripts/capture-trace-screenshot.mjs <traceId> <outputPath>
import { chromium } from "playwright";

const [traceId, outPath] = process.argv.slice(2);
if (!traceId || !outPath) {
  console.error("Usage: node capture-trace-screenshot.mjs <traceId> <outputPath>");
  process.exit(1);
}

const url =
  "http://localhost:3000/explore?left=" +
  encodeURIComponent(
    JSON.stringify({
      datasource: "tempo",
      queries: [{ query: traceId, queryType: "traceql" }],
      range: { from: "now-1h", to: "now" },
    }),
  );

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1400 } });
await page.goto(url, { waitUntil: "networkidle" });
await page.getByRole("button", { name: "Run query" }).click();
await page.waitForSelector("text=spans", { timeout: 15000 });
await page.waitForTimeout(1500);
await page.screenshot({ path: outPath, fullPage: true });
await browser.close();
console.log("Saved", outPath);

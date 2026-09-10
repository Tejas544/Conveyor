import { defineConfig, devices } from '@playwright/test'

/**
 * PLAN.md Phase 8. Requires the backend stack already running (`docker compose up -d` from the
 * repo root, plus `make seed`) -- these tests drive the real REST/SSE pipeline, the same "no
 * fake done" standard the rest of this project holds itself to, not a mocked frontend. The Vite
 * dev server is what `webServer` starts; it proxies to the already-running backend per
 * vite.config.ts.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  // These tests drive a real, shared, stateful backend (not a mocked or per-test-isolated
  // fixture) -- the "make this one fail" control arms payment-service's gateway globally, so
  // running specs concurrently lets one test's arm leak into another's assertions. Sequential by
  // design, the same tradeoff the project's own e2e Maven module makes with dedicated compose
  // stacks per scenario, just without the luxury of spinning up a second stack here.
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 30_000,
  },
})

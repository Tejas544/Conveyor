import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vitest/config'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    proxy: {
      // Local dev only: each backend service's own port, matching docker-compose.yml.
      '/api/v1/orders': 'http://localhost:8081',
      '/api/v1/auth': 'http://localhost:8081',
      '/api/v1/stream': 'http://localhost:8081',
      '/api/v1/sagas': 'http://localhost:8084',
      '/api/v1/inventory': 'http://localhost:8082',
      '/api/v1/catalog': 'http://localhost:8082',
      '/api/v1/payments': 'http://localhost:8083',
      // ARCHITECTURE.md §10.4: payment-service's chaos-profile-only test surface -- deliberately
      // outside /api/v1 (PaymentTestController), not part of the versioned public API.
      '/test/failure-mode': 'http://localhost:8083',
      '/api/v1/shipments': 'http://localhost:8085',
      '/api/v1/notifications': 'http://localhost:8085',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    // ./e2e is Playwright's own test directory (npx playwright test) -- Vitest's default glob
    // would otherwise also try to run those files with the wrong test runner.
    exclude: ['**/node_modules/**', './e2e/**'],
  },
})

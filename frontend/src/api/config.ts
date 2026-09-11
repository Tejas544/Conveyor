/**
 * Phase 14: the built dashboard is deployable as a static SPA (GitHub Pages) independent of
 * whichever backend it points at (ARCHITECTURE.md §15.2) — every prior phase's dev/compose/kind
 * setup made this a non-question (same origin via the Vite dev proxy, or never statically hosted at
 * all). Each variable defaults to `''` (a relative path), which reproduces that exact prior
 * behaviour unchanged: only setting one of these at build time (`vite build --mode production` with
 * `VITE_ORDER_API_URL` etc. in the environment) opts a deployment into pointing at a separate origin.
 */
function apiBaseUrl(env: string | undefined): string {
  return env && env.length > 0 ? env.replace(/\/$/, '') : ''
}

export const API_BASE_URLS = {
  order: apiBaseUrl(import.meta.env.VITE_ORDER_API_URL as string | undefined),
  inventory: apiBaseUrl(import.meta.env.VITE_INVENTORY_API_URL as string | undefined),
  payment: apiBaseUrl(import.meta.env.VITE_PAYMENT_API_URL as string | undefined),
  saga: apiBaseUrl(import.meta.env.VITE_SAGA_API_URL as string | undefined),
  dispatch: apiBaseUrl(import.meta.env.VITE_DISPATCH_API_URL as string | undefined),
} as const

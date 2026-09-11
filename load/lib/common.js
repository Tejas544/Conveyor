// Phase 12 (PLAN.md) — shared helpers for every k6 scenario in this directory.
// Run containerized (`grafana/k6`) via the `load` Makefile target, addressing the stack through
// its *published* host ports (`host.docker.internal`), not the internal `conveyor_conveyor`
// compose network's bare service DNS names (`order-service`, ...). This isn't just a style
// choice: a bare, dot-less hostname is a Public-Suffix-List "public suffix" as far as RFC 6265
// cookie handling is concerned, and both curl and k6's Go-based per-VU cookie jar silently
// *drop* any Set-Cookie for one (confirmed live: `curl` logs `cookie 'refreshToken' dropped,
// domain '[file]' must not set cookies for 'order-service'`) — which broke POST /auth/refresh's
// HttpOnly refresh-token cookie (ADR-5) during this phase's first soak run (240/66457 refresh
// calls 401'd, silently recovered by ensureFreshToken's re-login fallback with zero effect on
// the actual results — see BUGS.md). Not a real product defect: an actual browser client always
// reaches this system via `localhost` or a real registrable domain (both PSL-exempt), never the
// service mesh's own internal name — `host.docker.internal` reproduces that same shape for a
// containerized load generator. Override via -e for a host-installed k6 instead.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

export const ORDER_BASE = __ENV.ORDER_BASE_URL || 'http://host.docker.internal:8081/api/v1';
export const INVENTORY_BASE =
  __ENV.INVENTORY_BASE_URL || 'http://host.docker.internal:8082/api/v1';

export const SKU_COUNT = 50;

// ARCHITECTURE.md §10.1 / order-service's CreateOrderItemRequest — currency and per-item
// unitPrice are required (CONTEXT.md's Phase 3 Key Decisions Log: the client supplies both,
// order-service never calls inventory-service's catalog synchronously to resolve a price).
const CURRENCY = 'USD';
const UNIT_PRICE = '19.99';

// conveyor_saga_active / a saga that hasn't reached CONFIRMED/CANCELLED within this long during
// a load run is counted as "stuck" for this run's purposes — not necessarily lost forever (a slow
// saga might still land after the poll gives up), but it did not meet the latency bound a real
// customer would tolerate, which is what this metric is for.
export const POLL_TIMEOUT_SECONDS = Number(__ENV.POLL_TIMEOUT_SECONDS || 30);
const POLL_INTERVAL_SECONDS = 0.5;

export const sagaCompletionLatency = new Trend('saga_completion_latency_ms', true);
export const ordersPlaced = new Counter('orders_placed_total');
export const ordersConfirmed = new Counter('orders_confirmed_total');
export const ordersCancelled = new Counter('orders_cancelled_total');
export const ordersStuck = new Counter('orders_stuck_total');
export const placeOrderFailed = new Rate('place_order_failed');
export const pollFailed = new Rate('poll_order_failed');

function randomUuid() {
  // Load-generation only — not cryptographically significant, just needs to be a valid UUID the
  // server accepts and unique enough not to collide across a run.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function opsUsername() {
  return __ENV.OPS_USERNAME || 'ops';
}
export function opsPassword() {
  return __ENV.OPS_PASSWORD || 'ops_local_dev_only';
}
export function adminUsername() {
  return __ENV.ADMIN_USERNAME || 'admin';
}
export function adminPassword() {
  return __ENV.ADMIN_PASSWORD || 'admin_local_dev_only';
}

/**
 * Lazily logs this VU in as `ops` on its first call (populating this VU's own cookie jar with
 * the refresh cookie) and refreshes on every subsequent call once the access token is stale.
 * The one piece of per-VU mutable state every scenario script needs — callers hold the `let`.
 */
export function vuOpsSession(existing) {
  if (!existing) {
    return login(opsUsername(), opsPassword());
  }
  return ensureFreshToken(existing, opsUsername(), opsPassword());
}

export function randomSku() {
  const i = Math.floor(Math.random() * SKU_COUNT) + 1;
  return `SKU-${String(i).padStart(4, '0')}`;
}

/**
 * POST /auth/login (public) — used for the OPS token that polling GET /orders/{id} requires.
 * The refresh token is never in the JSON body (AuthController's Javadoc: it's an `HttpOnly`
 * cookie scoped to /api/v1/auth, deliberately kept out of reach of JS — ADR-5's XSS mitigation),
 * so it is never read here either; it rides in this VU's own k6 cookie jar automatically, and
 * ensureFreshToken's POST /auth/refresh below relies on that jar rather than threading the
 * cookie through state by hand.
 */
export function login(username, password) {
  const res = http.post(
    `${ORDER_BASE}/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'auth_login' } },
  );
  check(res, { 'login: 200': (r) => r.status === 200 });
  if (res.status !== 200) {
    throw new Error(`login failed for ${username}: ${res.status} ${res.body}`);
  }
  const body = res.json();
  return {
    accessToken: body.accessToken,
    expiresInSeconds: body.expiresIn,
    mintedAtMs: Date.now(),
  };
}

/**
 * Access tokens are 15-minute JWTs (JwtIssuerProperties). Refresh once >70% of the TTL has
 * elapsed so a 30-minute soak run never hits a 401 mid-poll. Must run in the same VU that logged
 * in (so the HttpOnly refresh cookie set at login time is still in that VU's cookie jar) — never
 * called from setup()/a different VU's session.
 */
export function ensureFreshToken(session, username, password) {
  const ageSeconds = (Date.now() - session.mintedAtMs) / 1000;
  if (ageSeconds < session.expiresInSeconds * 0.7) {
    return session;
  }
  const res = http.post(`${ORDER_BASE}/auth/refresh`, null, {
    tags: { name: 'auth_refresh' },
  });
  if (res.status !== 200) {
    // Fall back to a fresh login rather than failing the whole VU on one bad refresh.
    return login(username, password);
  }
  const body = res.json();
  return {
    accessToken: body.accessToken,
    expiresInSeconds: body.expiresIn,
    mintedAtMs: Date.now(),
  };
}

function authHeaders(session) {
  return { Authorization: `Bearer ${session.accessToken}`, 'Content-Type': 'application/json' };
}

/**
 * Bulk-pads every seeded SKU's stock via POST /inventory/{sku}/adjust (ADMIN) so a load run
 * never legitimately runs a SKU dry and confuses "saga slow under load" with "saga correctly
 * compensated for insufficient stock". CatalogSeedRunner seeds 20-110 units per SKU (Phase 2)
 * — nowhere near enough for a sustained multi-VU soak. Idempotent to re-run (each call is an
 * additive audited adjustment, per ADR's own design — running this twice just pads twice).
 */
export function padInventory(adminSession, unitsPerSku) {
  for (let i = 1; i <= SKU_COUNT; i += 1) {
    const sku = `SKU-${String(i).padStart(4, '0')}`;
    const res = http.post(
      `${INVENTORY_BASE}/inventory/${sku}/adjust`,
      JSON.stringify({ delta: unitsPerSku, reason: 'PHASE_12_LOAD_TEST_PAD' }),
      { headers: authHeaders(adminSession), tags: { name: 'inventory_adjust' } },
    );
    check(res, { 'inventory pad: 200': (r) => r.status === 200 });
  }
}

/** POST /orders — public, no auth. Returns {orderId, sagaId, submittedAtMs} or null on failure. */
export function placeOrder() {
  const payload = JSON.stringify({
    customerId: randomUuid(),
    items: [{ sku: randomSku(), quantity: 1, unitPrice: UNIT_PRICE }],
    shippingAddress: {
      line1: '1 Load Test Way',
      city: 'Bengaluru',
      postalCode: '560001',
      country: 'IN',
    },
    currency: CURRENCY,
    paymentMethodToken: 'tok_test_visa',
  });
  const submittedAtMs = Date.now();
  const res = http.post(`${ORDER_BASE}/orders`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'place_order' },
  });
  ordersPlaced.add(1);
  const ok = check(res, { 'place order: 202': (r) => r.status === 202 });
  placeOrderFailed.add(!ok);
  if (!ok) {
    return null;
  }
  const body = res.json();
  return { orderId: body.orderId, sagaId: body.sagaId, submittedAtMs };
}

/**
 * Polls GET /orders/{id} until it reaches a terminal status (CONFIRMED/CANCELLED) or
 * POLL_TIMEOUT_SECONDS elapses. Records saga_completion_latency_ms (HTTP 202 to terminal — the
 * number PLAN.md's Phase 12 goal names as the one that actually matters, not HTTP response time)
 * tagged by outcome, and the confirmed/cancelled/stuck counters.
 */
export function pollUntilTerminal(session, order) {
  if (!order) {
    return;
  }
  const deadlineMs = order.submittedAtMs + POLL_TIMEOUT_SECONDS * 1000;
  let status = 'PLACED';
  while (Date.now() < deadlineMs) {
    const res = http.get(`${ORDER_BASE}/orders/${order.orderId}`, {
      headers: authHeaders(session),
      tags: { name: 'poll_order' },
    });
    const ok = check(res, { 'poll order: 200': (r) => r.status === 200 });
    pollFailed.add(!ok);
    if (ok) {
      status = res.json().status;
      if (status === 'CONFIRMED' || status === 'CANCELLED') {
        break;
      }
    }
    sleep(POLL_INTERVAL_SECONDS);
  }
  const latencyMs = Date.now() - order.submittedAtMs;
  if (status === 'CONFIRMED') {
    sagaCompletionLatency.add(latencyMs, { outcome: 'confirmed' });
    ordersConfirmed.add(1);
  } else if (status === 'CANCELLED') {
    sagaCompletionLatency.add(latencyMs, { outcome: 'cancelled' });
    ordersCancelled.add(1);
  } else {
    ordersStuck.add(1);
  }
}

/** One virtual customer: place an order, wait to see it reach a terminal state. */
export function placeAndPoll(session) {
  const order = placeOrder();
  pollUntilTerminal(session, order);
}

// PLAN.md Phase 12 — smoke test. A handful of VUs, short duration: proves the load-test
// pipeline itself (auth, order placement, polling, custom metrics) works end to end before any
// scenario that costs real wall-clock time is run. Not a performance measurement.
import { sleep } from 'k6';
import {
  login,
  padInventory,
  placeAndPoll,
  vuOpsSession,
  adminUsername,
  adminPassword,
} from './lib/common.js';

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 2,
      duration: '30s',
    },
  },
  thresholds: {
    place_order_failed: ['rate==0'],
    poll_order_failed: ['rate==0'],
    orders_stuck_total: ['count==0'],
    saga_completion_latency_ms: ['p(95)<10000'],
  },
};

export function setup() {
  const admin = login(adminUsername(), adminPassword());
  padInventory(admin, 500);
}

let opsSession = null;

export default function () {
  opsSession = vuOpsSession(opsSession);
  placeAndPoll(opsSession);
  sleep(1);
}

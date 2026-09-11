// PLAN.md Phase 12 — sustained soak at the concurrency identified as the knee by ramp.js.
// Exit criterion: >=30 min at the knee with zero invariant violations (conveyor-verifier runs
// continuously throughout — see RESULTS.md for the query) and zero lost orders (orders_stuck_total
// stays 0). SOAK_VUS defaults to a conservative placeholder; set it explicitly from ramp.js's
// result before running this for real (see RESULTS.md's Phase 12 methodology for the value used).
import { sleep } from 'k6';
import {
  login,
  padInventory,
  placeAndPoll,
  vuOpsSession,
  adminUsername,
  adminPassword,
} from './lib/common.js';

const SOAK_VUS = Number(__ENV.SOAK_VUS || 40);
const SOAK_DURATION = __ENV.SOAK_DURATION || '30m';

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-vus',
      vus: SOAK_VUS,
      duration: SOAK_DURATION,
      gracefulStop: '30s',
    },
  },
  thresholds: {
    place_order_failed: ['rate<0.01'],
    orders_stuck_total: ['count==0'],
    saga_completion_latency_ms: ['p(99)<8000'],
  },
};

export function setup() {
  const admin = login(adminUsername(), adminPassword());
  // 30 minutes at SOAK_VUS concurrent customers, each placing an order roughly every ~1-2s —
  // pad heavily so no SKU legitimately runs dry mid-soak (a real stockout would correctly
  // compensate, but would contaminate "is the system slow" with "did it run out of a SKU").
  padInventory(admin, 100000);
}

let opsSession = null;

export default function () {
  opsSession = vuOpsSession(opsSession);
  placeAndPoll(opsSession);
  sleep(0.5);
}

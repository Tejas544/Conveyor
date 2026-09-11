// PLAN.md Phase 12 — ramp-to-the-knee. A sequence of fixed-concurrency steps (not a smooth ramp)
// so each step's throughput/latency/error-rate can be read off independently from k6's own
// per-scenario metric tags, rather than inferring stage boundaries after the fact from a single
// continuously-ramping VU count. Each step name (vus_N) is a k6 `scenario` tag on every metric
// sample, which is what RESULTS.md's per-concurrency table is built from
// (`--summary-export=results/ramp-summary.json`, or the full time series via
// `K6_OUT=json=... make load-ramp` for the p99-per-step breakdown).
import { sleep } from 'k6';
import {
  login,
  padInventory,
  placeAndPoll,
  vuOpsSession,
  adminUsername,
  adminPassword,
} from './lib/common.js';

const STEP_DURATION = '90s';
const STEP_VUS = [5, 10, 20, 40, 80, 120, 160];

function buildScenarios() {
  const scenarios = {};
  STEP_VUS.forEach((vus, i) => {
    scenarios[`vus_${vus}`] = {
      executor: 'constant-vus',
      vus,
      duration: STEP_DURATION,
      startTime: `${i * 90}s`,
      gracefulStop: '30s',
      exec: 'run',
    };
  });
  return scenarios;
}

export const options = {
  scenarios: buildScenarios(),
  thresholds: {
    // Not abortOnFail — the whole point of this scenario is to find where these get breached,
    // not to stop the instant they do. Every step's numbers land in RESULTS.md regardless.
    place_order_failed: [{ threshold: 'rate<0.01', abortOnFail: false }],
    saga_completion_latency_ms: [{ threshold: 'p(99)<8000', abortOnFail: false }],
  },
};

export function setup() {
  const admin = login(adminUsername(), adminPassword());
  // Peak step is 160 concurrent VUs each placing ~1 order every few seconds for 90s per step
  // across 7 steps — pad generously so no step's numbers are contaminated by a real stockout.
  padInventory(admin, 20000);
}

let opsSession = null;

export function run() {
  opsSession = vuOpsSession(opsSession);
  placeAndPoll(opsSession);
  sleep(0.2);
}

// PLAN.md Phase 12 — spike: a sudden burst far above steady-state, then back down, to see
// whether the pipeline degrades gracefully (queues and catches up) or drops orders under a
// step-function load change rather than a gradual ramp.
import { sleep } from 'k6';
import {
  login,
  padInventory,
  placeAndPoll,
  vuOpsSession,
  adminUsername,
  adminPassword,
} from './lib/common.js';

const BASELINE_VUS = Number(__ENV.SPIKE_BASELINE_VUS || 5);
const PEAK_VUS = Number(__ENV.SPIKE_PEAK_VUS || 150);

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: BASELINE_VUS,
      stages: [
        { duration: '1m', target: BASELINE_VUS }, // baseline
        { duration: '10s', target: PEAK_VUS }, // the spike
        { duration: '2m', target: PEAK_VUS }, // hold at peak
        { duration: '10s', target: BASELINE_VUS }, // sudden drop
        { duration: '2m', target: BASELINE_VUS }, // recovery / drain
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    orders_stuck_total: ['count==0'],
  },
};

export function setup() {
  const admin = login(adminUsername(), adminPassword());
  padInventory(admin, 20000);
}

let opsSession = null;

export default function () {
  opsSession = vuOpsSession(opsSession);
  placeAndPoll(opsSession);
  sleep(0.2);
}

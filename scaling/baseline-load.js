// PLAN.md Phase 15 — a deliberately light, fixed-concurrency probe (15 VUs, well under
// order-service's CPU-HPA threshold) to measure this same kind cluster's single-replica
// throughput, for a fair "throughput at n replicas vs. 1 replica" comparison against
// trigger-load.js's 60-VU run (RESULTS.md) — same host, same topology, only concurrency differs.
import { sleep } from 'k6';
import {
  login,
  padInventory,
  placeAndPoll,
  vuOpsSession,
  adminUsername,
  adminPassword,
} from '../load/lib/common.js';

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-vus',
      vus: 15,
      duration: '90s',
    },
  },
};

export function setup() {
  const admin = login(adminUsername(), adminPassword());
  padInventory(admin, 2000);
}

let opsSession = null;

export default function run() {
  opsSession = vuOpsSession(opsSession);
  placeAndPoll(opsSession);
  sleep(0.2);
}

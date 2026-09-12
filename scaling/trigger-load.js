// PLAN.md Phase 15 — a smaller, gentler load profile than load/ramp.js's full 7-step/160-VU knee
// search, reusing the same lib/common.js helpers. This phase found live (BUG-0047, RESULTS.md)
// that pushing order-service to its 5-replica ceiling *and* saga-orchestrator to a high replica
// count simultaneously exceeds this specific dev host's real capacity — the goal here is only to
// cross both HPAs' scale-up thresholds cleanly, not to re-run Phase 12's own knee-finding exercise.
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
    trigger: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 60 },
        { duration: '3m', target: 60 },
        { duration: '10s', target: 0 },
      ],
      gracefulStop: '30s',
    },
  },
};

export function setup() {
  const admin = login(adminUsername(), adminPassword());
  padInventory(admin, 5000);
}

let opsSession = null;

export default function run() {
  opsSession = vuOpsSession(opsSession);
  placeAndPoll(opsSession);
  sleep(0.2);
}

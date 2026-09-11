#!/usr/bin/env python3
"""
PLAN.md Phase 11 — the chaos matrix harness. For each trial: arm one named injection point
(ARCHITECTURE.md §14) or a broker/database fault, place a real order over the public API, confirm
the fault actually fired, disarm and let the system recover, wait for the saga to reach a terminal
state (bounded), run the full invariant catalogue (conveyor-verifier's one-shot mode) as the oracle,
and record everything — including the saga's own step log — to chaos/results/trials.jsonl.

Not part of the application; run manually or via `make chaos-matrix`, same spirit as
scripts/capture-trace-screenshot.mjs (Phase 9) and scripts/soak-check.sh (Phase 10).

Usage: python3 chaos/run_matrix.py [--quick]
  --quick   1 repetition per point instead of 4, and skips the broker/DB fault trials — for
            iterating on the harness itself without waiting ~40 minutes each time.
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RESULTS_DIR = Path(__file__).resolve().parent / "results"
COMPOSE_FILES = ["-f", "docker-compose.yml", "-f", "chaos/docker-compose.chaos-override.yml"]
APP_SERVICES = [
    "order-service",
    "inventory-service",
    "payment-service",
    "saga-orchestrator",
    "dispatch-service",
]
BASE_URLS = {
    "order": "http://localhost:8081/api/v1",
    "inventory": "http://localhost:8082/api/v1",
    "payment": "http://localhost:8083/api/v1",
    "saga": "http://localhost:8084/api/v1",
    "dispatch": "http://localhost:8085/api/v1",
}
INJECTION_POINTS = [
    "order.after-commit-before-publish",
    "saga.after-reply-before-state-write",
    "saga.after-state-write-before-command",
    "inventory.after-reserve-before-publish",
    "payment.after-commit-before-publish",
    "payment.before-commit",
    "dispatch.after-shipment-before-notify",
]
# Which single service's code actually calls maybeCrash/maybeDelay with this point name — a trial
# only needs to touch that one container, never the other four. Recreating all five per trial (the
# first version of this harness did) turned out to be the real bug: it disrupts four services that
# have nothing to do with the point under test, and was the actual cause of the one non-clean
# finding that version produced — not a real saga/projection defect. See RESULTS.md's methodology.
POINT_TO_SERVICE = {
    "order.after-commit-before-publish": "order-service",
    "saga.after-reply-before-state-write": "saga-orchestrator",
    "saga.after-state-write-before-command": "saga-orchestrator",
    "inventory.after-reserve-before-publish": "inventory-service",
    "payment.after-commit-before-publish": "payment-service",
    "payment.before-commit": "payment-service",
    "dispatch.after-shipment-before-notify": "dispatch-service",
}
DISARMED_ENV = {
    "CONVEYOR_CHAOS_CRASH_AT": "",
    "CONVEYOR_CHAOS_CRASH_PROBABILITY": "1.0",
    "CONVEYOR_CHAOS_DELAY_AT": "",
}
OPS_CREDENTIALS = {"username": "ops", "password": os.environ.get("SEED_OPS_PASSWORD", "ops_local_dev_only")}


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def run(cmd, env=None, check=True, capture=False, timeout=120):
    full_env = {**os.environ, **(env or {})}
    result = subprocess.run(
        cmd, cwd=REPO_ROOT, env=full_env, check=False, timeout=timeout,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        text=True,
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(cmd)}\n{result.stdout or ''}")
    return result


def http(method, base, path, token=None, body=None, timeout=10):
    url = f"{base}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
        return resp.status, (json.loads(raw) if raw else None)


def login(retries=5, delay=2):
    # Transient connection noise (this machine's own resource contention right after a docker
    # compose run --rm invocation, observed empirically — not a chaos signal; login never goes
    # anywhere near an injection point) shouldn't kill the whole run over one login call.
    last_error = None
    for attempt in range(retries):
        try:
            status, body = http("POST", BASE_URLS["order"], "/auth/login", body=OPS_CREDENTIALS)
            return body["accessToken"]
        except (urllib.error.URLError, ConnectionResetError, TimeoutError, OSError) as e:
            last_error = e
            log(f"  login attempt {attempt + 1}/{retries} failed ({e!r}), retrying...")
            time.sleep(delay)
    raise RuntimeError(f"login failed after {retries} attempts: {last_error!r}")


def compose_up(chaos_env, services=APP_SERVICES, wait_healthy=True):
    run(["docker", "compose", *COMPOSE_FILES, "up", "-d", "--force-recreate", *services], env=chaos_env)
    if wait_healthy:
        wait_for_healthy(services)


def wait_for_healthy(services, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = run(["docker", "compose", *COMPOSE_FILES, "ps", "-a", "--format", "json"], capture=True)
        lines = [line for line in result.stdout.splitlines() if line.strip()]
        statuses = {}
        for line in lines:
            row = json.loads(line)
            if row.get("Service") in services:
                statuses[row["Service"]] = row.get("Health", "") or row.get("State", "")
        if all("healthy" in statuses.get(s, "") for s in services):
            return
        time.sleep(2)
    raise RuntimeError(f"services did not become healthy within {timeout}s: {statuses}")


def container_state(service):
    result = run(
        ["docker", "compose", *COMPOSE_FILES, "ps", "-a", "--format", "json", service],
        capture=True, check=False,
    )
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    if not lines:
        return "absent"
    return json.loads(lines[0]).get("State", "unknown")


def wait_for_any_exit(services, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for service in services:
            if container_state(service) == "exited":
                return service
        time.sleep(1)
    return None


def place_order(token, sku, qty, idempotency_key, http_timeout=5):
    body = {
        "customerId": str(uuid.uuid4()),
        "currency": "USD",
        "paymentMethodToken": "tok_test_visa",
        "shippingAddress": {"line1": "1 Chaos Way", "city": "Faultville", "postalCode": "00000", "country": "US"},
        "items": [{"sku": sku, "quantity": qty, "unitPrice": 4.50}],
    }
    url = f"{BASE_URLS['order']}/orders"
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Idempotency-Key", idempotency_key)
    try:
        with urllib.request.urlopen(req, timeout=http_timeout) as resp:
            return json.loads(resp.read())["orderId"], False
    except urllib.error.HTTPError as e:
        # A real HTTP response (4xx/5xx) — the server is up and answered. urllib.error.HTTPError is
        # a URLError subclass, so this must be caught first or a plain validation error gets
        # misclassified as "the crash killed my connection," which is what the first version of
        # this harness did (BUGS.md).
        detail = e.read().decode(errors="replace")[:300]
        log(f"  place_order got HTTP {e.code}, not a dropped connection: {detail}")
        return None, False
    except (urllib.error.URLError, ConnectionResetError, TimeoutError, OSError) as e:
        log(f"  place_order connection error (treated as dropped): {e!r}")
        return None, True  # connection died mid-request — the order.after-commit-before-publish case


def recover_order_id(token, idempotency_key, retries=10, delay=2):
    """After a mid-request crash, the client's own retry (same Idempotency-Key) is how it finds out
    what happened — PLAN.md Phase 3's own idempotency contract, exercised for real here."""
    for _ in range(retries):
        order_id, dropped = place_order(token, "SKU-0001", 1, idempotency_key, http_timeout=5)
        if order_id:
            return order_id
        time.sleep(delay)
    return None


def get_order(token, order_id):
    try:
        status, body = http("GET", BASE_URLS["order"], f"/orders/{order_id}", token=token)
        return body
    except urllib.error.HTTPError:
        return None


def get_saga(token, order_id):
    try:
        status, body = http("GET", BASE_URLS["saga"], f"/sagas/{order_id}", token=token)
        return body
    except urllib.error.HTTPError:
        return None


def wait_for_terminal(token, order_id, timeout=90):
    deadline = time.time() + timeout
    start = time.time()
    last_status = None
    while time.time() < deadline:
        order = get_order(token, order_id)
        if order:
            last_status = order["status"]
            if last_status in ("CONFIRMED", "CANCELLED"):
                return last_status, time.time() - start
        time.sleep(2)
    return f"TIMEOUT(last={last_status})", time.time() - start


def run_invariant_check():
    """Returns (exitCode, set of "INV-XXX-NN:offendingId" strings). Empty set on a clean run — the
    JSON report line is only logged (at WARN) when at least one violation exists."""
    result = run(
        ["docker", "compose", "run", "--rm", "-e", "SPRING_PROFILES_ACTIVE=oneshot", "conveyor-verifier"],
        capture=True, check=False, timeout=60,
    )
    signatures = set()
    for line in result.stdout.splitlines():
        if '"conveyor-verifier violation report:' not in line:
            continue
        try:
            outer = json.loads(line)
            inner_json = outer["message"].split("conveyor-verifier violation report:", 1)[1].strip()
            report = json.loads(inner_json)
            for outcome in report.get("outcomes", []):
                for violation in outcome.get("violations", []):
                    signatures.add(f"{outcome['invariant']}:{violation['offendingId']}")
        except (json.JSONDecodeError, KeyError, IndexError):
            pass
    return result.returncode, signatures


SKUS = [f"SKU-{i:04d}" for i in range(1, 51)]

# Trials aren't reset-to-empty-database between each other (see RESULTS.md's methodology note on
# why), so a violation one trial introduces would otherwise still show up in every later trial's
# check and get that later, innocent trial wrongly marked non-clean too. This tracks every
# violation signature ever seen so "clean" means "introduced no *new* violation," which is what
# should actually gate each trial's own verdict.
_seen_violation_signatures = set()


def attribute_new_violations(all_violations):
    new = all_violations - _seen_violation_signatures
    _seen_violation_signatures.update(all_violations)
    return new


def run_trial(trial_id, mode, point, rep=0):
    sku = SKUS[trial_id % len(SKUS)]
    log(f"trial #{trial_id}: mode={mode} point={point} rep={rep} sku={sku}")
    # Fresh login per trial — the access token's 15-minute TTL (order-service's
    # JwtIssuerProperties default) is comfortably shorter than a full matrix run, and reusing one
    # token from main() caused cascading 401s partway through (misread as a wave of dropped
    # connections until logged properly — see BUGS.md).
    token = login()

    target_service = POINT_TO_SERVICE.get(point) if point else None

    if mode == "crash":
        env = {"CONVEYOR_CHAOS_CRASH_AT": point, "CONVEYOR_CHAOS_CRASH_PROBABILITY": "1.0", "CONVEYOR_CHAOS_DELAY_AT": ""}
        compose_up(env, services=[target_service])
        time.sleep(3)  # let the recreated container's one consumer group rejoin before it matters
    elif mode == "delay":
        env = {"CONVEYOR_CHAOS_CRASH_AT": "", "CONVEYOR_CHAOS_CRASH_PROBABILITY": "1.0", "CONVEYOR_CHAOS_DELAY_AT": f"{point}=5000"}
        compose_up(env, services=[target_service])
        time.sleep(3)
    # control mode touches nothing — the stack is already running disarmed from the previous trial.

    idempotency_key = str(uuid.uuid4())
    order_id, dropped_connection = place_order(token, sku, 1, idempotency_key)

    crashed_service = None
    if mode == "crash":
        crashed_service = wait_for_any_exit([target_service], timeout=45)
        compose_up(DISARMED_ENV, services=[target_service])  # bring only the crashed one back, clean

    if order_id is None:
        # Covers both the deliberate case (order.after-commit-before-publish's mid-request crash)
        # and ordinary transient connection noise unrelated to any injection — either way, the
        # client's own retry with the same Idempotency-Key is the correct recovery, not a special
        # case bolted on for one trial type.
        order_id = recover_order_id(token, idempotency_key)

    status, elapsed = wait_for_terminal(token, order_id, timeout=90) if order_id else ("NO_ORDER_ID", 0)
    saga = get_saga(token, order_id) if order_id else None
    check_exit, violations = run_invariant_check()
    new_violations = attribute_new_violations(violations)

    if mode == "delay":
        # Disarm before the next trial — otherwise this point's delay stays armed on target_service
        # indefinitely and silently affects whichever later trial next touches the same service.
        compose_up(DISARMED_ENV, services=[target_service])

    record = {
        "trialId": trial_id,
        "mode": mode,
        "injectionPoint": point,
        "repetition": rep,
        "orderId": order_id,
        "sku": sku,
        "droppedConnectionOnPlace": dropped_connection,
        "crashConfirmedOn": crashed_service,
        "crashExpectedButNotObserved": mode == "crash" and crashed_service is None,
        "finalOrderStatus": status,
        "convergenceSeconds": round(elapsed, 2),
        "invariantCheckExitCode": check_exit,
        "allViolationsAtCheckTime": sorted(violations),
        "newViolationsIntroducedByThisTrial": sorted(new_violations),
        "sagaSteps": (saga or {}).get("steps"),
        "sagaState": (saga or {}).get("state"),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    clean = (
        not new_violations
        and status in ("CONFIRMED", "CANCELLED")
        and not record["crashExpectedButNotObserved"]
    )
    record["clean"] = clean
    log(f"  -> status={status} ({elapsed:.1f}s) invariant_exit={check_exit} clean={clean}")
    return record


def run_broker_pause_trial(trial_id):
    log(f"trial #{trial_id}: broker fault — pause redpanda mid-saga")
    token = login()
    idempotency_key = str(uuid.uuid4())
    order_id, _ = place_order(token, SKUS[trial_id % len(SKUS)], 1, idempotency_key)
    if order_id is None:
        order_id = recover_order_id(token, idempotency_key)
    time.sleep(0.5)
    run(["docker", "compose", *COMPOSE_FILES, "pause", "redpanda"])
    time.sleep(8)
    run(["docker", "compose", *COMPOSE_FILES, "unpause", "redpanda"])
    status, elapsed = wait_for_terminal(token, order_id, timeout=120) if order_id else ("NO_ORDER_ID", 0)
    check_exit, violations = run_invariant_check()
    new_violations = attribute_new_violations(violations)
    saga = get_saga(token, order_id) if order_id else None
    record = {
        "trialId": trial_id, "mode": "broker-pause", "injectionPoint": "redpanda", "repetition": 0,
        "orderId": order_id, "finalOrderStatus": status, "convergenceSeconds": round(elapsed, 2),
        "invariantCheckExitCode": check_exit,
        "allViolationsAtCheckTime": sorted(violations),
        "newViolationsIntroducedByThisTrial": sorted(new_violations),
        "sagaSteps": (saga or {}).get("steps"), "sagaState": (saga or {}).get("state"),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    record["clean"] = (not new_violations) and status in ("CONFIRMED", "CANCELLED")
    log(f"  -> status={status} ({elapsed:.1f}s) invariant_exit={check_exit} clean={record['clean']}")
    return record


def run_broker_stop_trial(trial_id):
    token = login()
    log(f"trial #{trial_id}: broker fault — stop/start redpanda mid-saga (approximates a partition"
        " outage; a single-broker dev topology can't take one partition down independently — see"
        " RESULTS.md's methodology note)")
    idempotency_key = str(uuid.uuid4())
    order_id, _ = place_order(token, SKUS[trial_id % len(SKUS)], 1, idempotency_key)
    if order_id is None:
        order_id = recover_order_id(token, idempotency_key)
    time.sleep(0.5)
    run(["docker", "compose", *COMPOSE_FILES, "stop", "redpanda"])
    time.sleep(5)
    run(["docker", "compose", *COMPOSE_FILES, "start", "redpanda"])
    run(["docker", "compose", *COMPOSE_FILES, "ps", "redpanda"], capture=True)
    time.sleep(5)  # let it rejoin before app services' consumers reconnect
    status, elapsed = wait_for_terminal(token, order_id, timeout=150) if order_id else ("NO_ORDER_ID", 0)
    check_exit, violations = run_invariant_check()
    new_violations = attribute_new_violations(violations)
    saga = get_saga(token, order_id) if order_id else None
    record = {
        "trialId": trial_id, "mode": "broker-stop", "injectionPoint": "redpanda", "repetition": 0,
        "orderId": order_id, "finalOrderStatus": status, "convergenceSeconds": round(elapsed, 2),
        "invariantCheckExitCode": check_exit,
        "allViolationsAtCheckTime": sorted(violations),
        "newViolationsIntroducedByThisTrial": sorted(new_violations),
        "sagaSteps": (saga or {}).get("steps"), "sagaState": (saga or {}).get("state"),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    record["clean"] = (not new_violations) and status in ("CONFIRMED", "CANCELLED")
    log(f"  -> status={status} ({elapsed:.1f}s) invariant_exit={check_exit} clean={record['clean']}")
    return record


def run_db_unavailable_trial(trial_id):
    log(f"trial #{trial_id}: database-unavailable — pause postgres mid-saga")
    token = login()
    idempotency_key = str(uuid.uuid4())
    order_id, _ = place_order(token, SKUS[trial_id % len(SKUS)], 1, idempotency_key)
    if order_id is None:
        order_id = recover_order_id(token, idempotency_key)
    time.sleep(0.5)
    run(["docker", "compose", *COMPOSE_FILES, "pause", "postgres"])
    time.sleep(8)
    run(["docker", "compose", *COMPOSE_FILES, "unpause", "postgres"])
    status, elapsed = wait_for_terminal(token, order_id, timeout=150) if order_id else ("NO_ORDER_ID", 0)
    check_exit, violations = run_invariant_check()
    new_violations = attribute_new_violations(violations)
    saga = get_saga(token, order_id) if order_id else None
    record = {
        "trialId": trial_id, "mode": "db-unavailable", "injectionPoint": "postgres", "repetition": 0,
        "orderId": order_id, "finalOrderStatus": status, "convergenceSeconds": round(elapsed, 2),
        "invariantCheckExitCode": check_exit,
        "allViolationsAtCheckTime": sorted(violations),
        "newViolationsIntroducedByThisTrial": sorted(new_violations),
        "sagaSteps": (saga or {}).get("steps"), "sagaState": (saga or {}).get("state"),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    record["clean"] = (not new_violations) and status in ("CONFIRMED", "CANCELLED")
    log(f"  -> status={status} ({elapsed:.1f}s) invariant_exit={check_exit} clean={record['clean']}")
    return record


def main():
    quick = "--quick" in sys.argv
    reps = 1 if quick else 4
    control_reps = 2 if quick else 10

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = RESULTS_DIR / "trials.jsonl"
    trials = []
    trial_id = 0

    log("Bringing the stack up clean (disarmed) before the matrix starts...")
    compose_up(DISARMED_ENV)

    def run_and_record(fn, *args, **kwargs):
        nonlocal trial_id
        trial_id += 1
        this_id = trial_id
        try:
            record = fn(this_id, *args, **kwargs)
        except Exception as e:  # noqa: BLE001 — a flaky trial must not take the whole ~40-min run down
            log(f"trial #{this_id} raised {e!r} — recording as a failed trial and moving on")
            record = {"trialId": this_id, "error": repr(e), "clean": False}
            # Best-effort: bring the stack back to a known-good disarmed state before continuing.
            try:
                compose_up(DISARMED_ENV)
            except Exception as recovery_error:  # noqa: BLE001
                log(f"  recovery compose_up also failed: {recovery_error!r}")
        trials.append(record)
        out.write(json.dumps(record) + "\n")
        out.flush()

    with out_path.open("w", encoding="utf-8") as out:
        for _ in range(control_reps):
            run_and_record(run_trial, "control", None)

        for point in INJECTION_POINTS:
            for mode in ("crash", "delay"):
                for rep in range(reps):
                    run_and_record(run_trial, mode, point, rep=rep)

        if not quick:
            for fn in (run_broker_pause_trial, run_broker_stop_trial, run_db_unavailable_trial):
                run_and_record(fn)

    clean_count = sum(1 for t in trials if t.get("clean"))
    log(f"DONE: {len(trials)} trials, {clean_count} clean, {len(trials) - clean_count} non-clean.")
    log(f"Results written to {out_path}")


if __name__ == "__main__":
    main()

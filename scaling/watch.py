#!/usr/bin/env python3
"""PLAN.md Phase 15 — autoscaling instrumentation.

Polls the kind cluster at a fixed interval while a k6 load scenario (load/ramp.js, unmodified
from Phase 12) runs against it, and records everything RESULTS.md's Phase 15 section needs to
decompose scale-up/down latency: HPA current/desired replica counts and metric values, every
pod's own lifecycle-condition timestamps (creationTimestamp -> PodScheduled -> ContainersReady ->
Ready), and per-pod CPU from metrics-server. Kubernetes Events for both HPA objects are captured
separately (in full, not just this tick's view) so a "SuccessfulRescale" event's own timestamp —
the HPA-decision instant — is never missed between two polls.

Deliberately plain subprocess+kubectl, no python k8s client dependency, matching this project's
existing harness style (chaos/run_matrix.py, scripts/soak-check.sh) — a rigor-phase measurement
tool, not shipped application code.
"""
import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone

NAMESPACE = "conveyor"
WATCHED_SERVICES = ["order-service", "saga-orchestrator"]


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def kubectl_json(*args):
    # A tick that fails to reach the API server (or metrics-server) is noise to skip, not a
    # reason to kill an 18-minute unattended run — found live (BUG-0048): the first Phase 15
    # measurement run crashed outright on one slow `kubectl top` call under real load-generated
    # contention, losing every tick after it including the whole scale-down observation window.
    try:
        result = subprocess.run(
            ["kubectl", "-n", NAMESPACE, *args, "-o", "json"],
            capture_output=True, text=True, timeout=20,
        )
    except subprocess.TimeoutExpired:
        return None
    if result.returncode != 0:
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def kubectl_top_all_pods():
    # One call for the whole namespace, not one per watched service — halves the steady-state
    # API/metrics-server call rate this script makes every tick (BUG-0048's own lesson: this
    # script's own polling is extra load on a control plane that Phase 15's load test is already
    # stressing, so keep it as cheap as correctness allows).
    try:
        result = subprocess.run(
            ["kubectl", "-n", NAMESPACE, "top", "pods", "--no-headers"],
            capture_output=True, text=True, timeout=20,
        )
    except subprocess.TimeoutExpired:
        return {}
    if result.returncode != 0:
        return {}
    usage = {}
    for line in result.stdout.strip().splitlines():
        parts = line.split()
        if len(parts) >= 3:
            usage[parts[0]] = {"cpu": parts[1], "memory": parts[2]}
    return usage


def snapshot_hpa(name):
    doc = kubectl_json("get", f"hpa/{name}")
    if not doc:
        return None
    status = doc.get("status", {})
    return {
        "name": name,
        "currentReplicas": status.get("currentReplicas"),
        "desiredReplicas": status.get("desiredReplicas"),
        "currentMetrics": status.get("currentMetrics"),
        "conditions": status.get("conditions"),
    }


def snapshot_pods(svc, usage):
    doc = kubectl_json("get", "pods", "-l", f"app.kubernetes.io/name={svc}")
    pods = []
    if doc:
        for item in doc.get("items", []):
            meta = item.get("metadata", {})
            status = item.get("status", {})
            conditions = {c["type"]: c.get("lastTransitionTime") for c in status.get("conditions", [])}
            pods.append({
                "podName": meta.get("name"),
                "creationTimestamp": meta.get("creationTimestamp"),
                "phase": status.get("phase"),
                "podScheduled": conditions.get("PodScheduled"),
                "containersReady": conditions.get("ContainersReady"),
                "ready": conditions.get("Ready"),
                "cpu": usage.get(meta.get("name"), {}).get("cpu"),
                "memory": usage.get(meta.get("name"), {}).get("memory"),
            })
    return pods


def snapshot_events():
    doc = kubectl_json("get", "events", "--field-selector", "involvedObject.kind=HorizontalPodAutoscaler")
    events = []
    if doc:
        for item in doc.get("items", []):
            events.append({
                "involvedObject": item.get("involvedObject", {}).get("name"),
                "reason": item.get("reason"),
                "message": item.get("message"),
                "firstTimestamp": item.get("firstTimestamp"),
                "lastTimestamp": item.get("lastTimestamp"),
                "count": item.get("count"),
            })
    return events


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=int, required=True, help="seconds to watch")
    parser.add_argument("--interval", type=float, default=3.0)
    parser.add_argument("--out", default="scaling/results/watch.jsonl")
    parser.add_argument("--events-out", default="scaling/results/events.jsonl")
    args = parser.parse_args()

    seen_event_keys = set()
    deadline = time.monotonic() + args.duration
    tick = 0
    with open(args.out, "a") as out_f, open(args.events_out, "a") as ev_f:
        while time.monotonic() < deadline:
            tick += 1
            ts = now_iso()
            usage = kubectl_top_all_pods()
            record = {
                "ts": ts,
                "tick": tick,
                "hpas": [s for s in (snapshot_hpa(n) for n in WATCHED_SERVICES) if s],
                "pods": {svc: snapshot_pods(svc, usage) for svc in WATCHED_SERVICES},
            }
            out_f.write(json.dumps(record) + "\n")
            out_f.flush()

            for event in snapshot_events():
                key = (event["involvedObject"], event["reason"], event["lastTimestamp"], event["count"])
                if key not in seen_event_keys:
                    seen_event_keys.add(key)
                    ev_f.write(json.dumps({"observedAt": ts, **event}) + "\n")
                    ev_f.flush()

            sleep_for = deadline - time.monotonic()
            time.sleep(min(args.interval, max(0.0, sleep_for)))
    print(f"watch complete: {tick} ticks written to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()

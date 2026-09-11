#!/bin/sh
# One-off soak-verification script for PLAN.md Phase 10's "zero violations on a clean system for a
# 30-minute soak" exit criterion. Not part of the app; run manually, same spirit as
# scripts/capture-trace-screenshot.mjs (Phase 9).
set -eu
END=$(( $(date +%s) + 1800 ))
i=0
while [ "$(date +%s)" -lt "$END" ]; do
  i=$((i + 1))
  CLEAN=$(curl -s http://localhost:8086/actuator/prometheus | grep 'conveyor_verifier_clean{' | awk '{print $2}')
  echo "$(date -u +%FT%TZ) check#$i conveyor_verifier_clean=$CLEAN"
  if [ "$CLEAN" != "1.0" ]; then
    echo "!!! NON-CLEAN STATE DETECTED !!!"
    curl -s http://localhost:8086/actuator/prometheus | grep invariant_violations || true
  fi
  sleep 60
done
echo "SOAK COMPLETE"

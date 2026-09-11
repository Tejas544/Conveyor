#!/usr/bin/env bash
# Local hygiene, not a cost concern (ARCHITECTURE.md §15 — kind runs on hardware already owned).
set -euo pipefail
kind delete cluster --name conveyor

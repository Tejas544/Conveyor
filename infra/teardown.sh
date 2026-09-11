#!/usr/bin/env bash
# PLAN.md Phase 14 / ARCHITECTURE.md §15.3, CLAUDE.md §9: local hygiene for the $0 executed path,
# plus documentation (not automation) of the one step that would matter if a human ever explicitly
# applies the costed AWS fallback (§15.4, infra/terraform/). Idempotent — every step below is safe
# to run against a stack that's already down.
set -uo pipefail
cd "$(dirname "$0")/.."

echo "==> docker compose down -v (local dev stack, including the observability profile's containers)"
docker compose --profile observability down -v

echo "==> kind cluster (scripts/kind-up.sh's target, if it's up)"
if command -v kind >/dev/null 2>&1 && kind get clusters 2>/dev/null | grep -qx conveyor; then
  ./scripts/kind-down.sh
else
  echo "    no 'conveyor' kind cluster found, nothing to do"
fi

cat <<'EOF'

==> AWS fallback path (ARCHITECTURE.md §15.4)
This project's automated pipeline never runs `terraform apply` (ADR-13,
.github/workflows/build.yml's verify-no-terraform-apply job) — there is nothing on AWS for this
script to tear down by default. If, and only if, a human has separately and explicitly run a real
`terraform apply` against infra/terraform/ (per that directory's own README.md), tear it down with:

    cd infra/terraform
    terraform destroy

Nothing above runs that automatically — an apply severe enough to bill real money gets an equally
deliberate destroy, not a script silently doing it on their behalf.
EOF

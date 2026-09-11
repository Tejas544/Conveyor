#!/bin/bash
# Regenerates src/api/generated/*.d.ts from each service's live OpenAPI spec.
# ADR-11: "TS API types generated from the OpenAPI specs so a backend change breaks the frontend
# build." Requires the stack running locally (`docker compose up -d` from the repo root).
set -eu
cd "$(dirname "$0")/.."

declare -A services=(
  [order]=8081
  [inventory]=8082
  [payment]=8083
  [saga]=8084
  [dispatch]=8085
)

mkdir -p src/api/generated
for name in "${!services[@]}"; do
  port="${services[$name]}"
  echo "Fetching $name (port $port)..."
  curl -sf "http://localhost:${port}/v3/api-docs" -o "/tmp/${name}-api.json"
  npx openapi-typescript "/tmp/${name}-api.json" -o "src/api/generated/${name}.d.ts"
done
echo "Done. Review the diff in src/api/generated/ before committing."

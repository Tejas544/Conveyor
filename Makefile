.PHONY: up down logs build test verify seed reset ps observability-up observability-down invariant-check chaos-matrix load

up:
	docker compose up -d --build

down:
	docker compose down

# ARCHITECTURE.md §11 (Phase 9): Prometheus + Grafana (localhost:3000, anonymous
# admin) + Tempo, alongside the always-on five services. `make up` first if the
# app containers aren't running yet.
observability-up:
	docker compose --profile observability up -d prometheus tempo grafana

observability-down:
	docker compose --profile observability stop prometheus tempo grafana

reset:
	docker compose down -v

logs:
	docker compose logs -f

ps:
	docker compose ps

build:
	./mvnw -q -DskipTests package

test:
	./mvnw test

verify:
	./mvnw verify

# PLAN.md Phase 2: demo users (order-service) + ~50 catalog SKUs with stock
# (inventory-service). Idempotent — safe to run against an already-seeded
# stack. Each is a one-shot container that exits once seeding completes.
seed:
	docker compose run --rm -e SPRING_PROFILES_ACTIVE=seed order-service
	docker compose run --rm -e SPRING_PROFILES_ACTIVE=seed inventory-service

# PLAN.md Phase 10: a single conveyor-verifier pass, exits non-zero on any inside-out violation
# (outside-in is diagnostic only — see OneShotRunner's Javadoc). Named invariant-check, not verify,
# because `verify` already means `./mvnw verify` in this Makefile (PLAN.md's own Phase 10 wording,
# "make verify for a one-shot run," collided with that — see CONTEXT.md's Key Decisions Log). Needs
# the rest of the stack already up (`make up` first) — this spins up a separate one-shot container
# alongside the always-on conveyor-verifier sidecar, it does not replace it.
invariant-check:
	docker compose run --rm -e SPRING_PROFILES_ACTIVE=oneshot conveyor-verifier

# PLAN.md Phase 11: the chaos matrix. Needs the stack already up and seeded (`make up && make
# seed`). Takes on the order of an hour for the full matrix; pass QUICK=1 for a 1-repetition,
# no-broker-fault smoke run while iterating on the harness itself.
chaos-matrix:
	python3 chaos/run_matrix.py $(if $(QUICK),--quick,)

# PLAN.md Phase 12: k6 load test, containerized (no host k6 install required — same reasoning as
# every other `make` target in this file being a thin docker/mvnw wrapper). Needs the rest of the
# stack already up and seeded (`make up && make seed`); `--profile observability` too if you want
# Tempo/Grafana available for the trace-based bottleneck analysis RESULTS.md's Phase 12 section
# describes. SCENARIO defaults to `smoke`; pass SCENARIO=ramp|soak|spike for the other three.
# `soak` additionally takes SOAK_VUS (set to the concurrency ramp.js identified as the knee).
# Addresses the stack via its published host ports (host.docker.internal), the same path a real
# client uses — not the internal conveyor_conveyor network's bare service names, which silently
# break the refresh-token cookie under RFC 6265's Public-Suffix-List rule (see load/lib/common.js
# and BUGS.md). --network conveyor_conveyor is still attached for parity with a stack that has no
# published ports (e.g. a future K8s ClusterIP-only target); host.docker.internal resolves either
# way on Docker Desktop.
load:
	mkdir -p load/results
	docker run --rm --network conveyor_conveyor \
		-v "$(CURDIR)/load:/scripts" \
		-e ORDER_BASE_URL=http://host.docker.internal:8081/api/v1 \
		-e INVENTORY_BASE_URL=http://host.docker.internal:8082/api/v1 \
		-e SOAK_VUS=$(SOAK_VUS) \
		-e SOAK_DURATION=$(SOAK_DURATION) \
		grafana/k6 run /scripts/$(or $(SCENARIO),smoke).js \
		--summary-export=/scripts/results/$(or $(SCENARIO),smoke)-summary.json

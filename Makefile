.PHONY: up down logs build test verify seed reset ps observability-up observability-down

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

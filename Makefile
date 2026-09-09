.PHONY: up down logs build test verify seed reset ps

up:
	docker compose up -d --build

down:
	docker compose down

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

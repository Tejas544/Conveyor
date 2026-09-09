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

# Phase 2 fills this in once there is a schema to seed against.
seed:
	@echo "make seed: no-op until Phase 2 (data layer) lands — see PLAN.md."

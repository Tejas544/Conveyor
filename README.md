# Conveyor

A cloud-native, event-driven order fulfillment platform. Saga-pattern
distributed transactions over Kafka, polyglot persistence (PostgreSQL +
MongoDB), and a live ops dashboard — the service-layer counterpart to
[Anvil](../Anvil)'s storage-engine-level 2PC.

> **Status: Phase 3 (Order Service) complete, Phase 4 (Inventory Service) next.**
> This README is a stub; it grows into the full project overview (architecture
> diagram, results, "what each part demonstrates") in Phase 16. See
> [`CONTEXT.md`](CONTEXT.md) for exactly where things stand right now.

## What this is

A customer places an order. The system reserves inventory, charges payment,
and confirms the order — or, if any step fails, correctly and observably
*compensates* (releases inventory, refunds payment) so the system never ends
up inconsistent. The whole pipeline is visible live on an operations
dashboard.

- **Backend:** five independently deployable Spring Boot services — order,
  inventory, payment, dispatch, and a dedicated saga orchestrator — talking
  over Kafka.
- **Data:** PostgreSQL for transactional state, MongoDB for document-shaped
  data, split deliberately (see `ARCHITECTURE.md` ADR-12).
- **Frontend:** a React/TypeScript ops dashboard, live via SSE.
- **Rigor:** an invariant checker, a chaos matrix, a load test, and an
  autoscaling measurement — see `PLAN.md` Stage D.

Full design: [`ARCHITECTURE.md`](ARCHITECTURE.md). Full build plan:
[`PLAN.md`](PLAN.md). Frozen requirements: [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md).

## Running it locally

Requires: Docker, Docker Compose. Maven is **not** required — the repo uses
the Maven Wrapper (`./mvnw`).

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Health endpoints once everything is up:

| Service | Port | Health |
|---|---|---|
| order-service | 8081 | http://localhost:8081/actuator/health |
| inventory-service | 8082 | http://localhost:8082/actuator/health |
| payment-service | 8083 | http://localhost:8083/actuator/health |
| saga-orchestrator | 8084 | http://localhost:8084/actuator/health |
| dispatch-service | 8085 | http://localhost:8085/actuator/health |

Each service publishes an OpenAPI spec at `/v3/api-docs` and a browsable UI at
`/swagger-ui.html` (e.g. http://localhost:8081/swagger-ui.html for orders).

Seed demo data (idempotent — safe to run again):

```bash
make seed   # ops/admin users + 50 catalog SKUs with stock
```

Place an order (order-service is the only service with real business logic so
far — see "Where things stand" below):

```bash
curl -X POST http://localhost:8081/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
        "customerId": "9b2ecb1a-0000-4000-8000-000000000003",
        "items": [{ "sku": "SKU-0001", "quantity": 2, "unitPrice": 9.99 }],
        "shippingAddress": { "line1": "1 Test St", "city": "Testville", "postalCode": "00000", "country": "IN" },
        "currency": "USD",
        "paymentMethodToken": "tok_test_visa"
      }'
# 202 Accepted, {"orderId": "...", "sagaId": null, "status": "PLACED"}

curl http://localhost:8081/api/v1/orders/<orderId>
curl http://localhost:8081/api/v1/orders/summary
```

`OrderPlaced` reaches `conveyor.order.events.v1` within ~200ms via the
transactional outbox poller — `docker compose logs order-service` shows it,
or consume the topic directly with `rpk topic consume` inside the `redpanda`
container.

## Where things stand

Only **order-service** has real business logic implemented (Phase 3): the
order aggregate and its guarded state machine, `POST/GET /orders`, the
transactional outbox + polling publisher (`conveyor-common`, reused by every
service from Phase 4 on), and a consumer that will project saga replies onto
order status once Phase 6 (saga-orchestrator) exists — a no-op today by
design. The other four services have their data layer (Phase 2: migrations,
JPA entities, Mongo validators) but no REST APIs or Kafka consumers/producers
yet; those land in Phases 4–7.

## Building and testing

```bash
./mvnw verify       # compile, unit + integration tests (Testcontainers), Spotless, Checkstyle
make up             # docker compose up -d --build
make test           # ./mvnw test
```

Integration tests spin up real PostgreSQL, MongoDB and Redpanda via
Testcontainers — Docker must be running.

## Deployment

Zero cloud cost, by design (see `ARCHITECTURE.md` ADR-13): kind/k3d + GHCR +
a free static host, not AWS EKS. A costed AWS path is documented and
Terraform-`plan`-validated but never applied. Details land in Phase 14's
`docs/DEPLOYMENT.md`.

## Project documents

| File | Purpose |
|---|---|
| [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) | Frozen requirements |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Design, ADRs, schemas, sequence diagrams |
| [`PLAN.md`](PLAN.md) | Phase-by-phase build plan |
| [`CONTEXT.md`](CONTEXT.md) | Current state — start here for "where are we" |
| [`BUGS.md`](BUGS.md) | Bug ledger |
| `RESULTS.md` | Chaos/load/autoscaling results (arrives in Stage D) |

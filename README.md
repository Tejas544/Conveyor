# Conveyor

A cloud-native, event-driven order fulfillment platform. Saga-pattern
distributed transactions over Kafka, polyglot persistence (PostgreSQL +
MongoDB), and a live ops dashboard — the service-layer counterpart to
[Anvil](../Anvil)'s storage-engine-level 2PC.

> **Status: Phases 4 (Inventory) and 5 (Payment) complete — `./mvnw verify`
> green across the full reactor. Phase 3 (Order) has one item left:
> `docker compose up` health, blocked on Docker Desktop instability (BUGS.md
> BUG-0008). Phase 6 (Saga Orchestrator) is next.**
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

Three of five services have real business logic implemented:

- **order-service** (Phase 3): the order aggregate and its guarded state
  machine, `POST/GET /orders`, the transactional outbox + polling publisher
  (`conveyor-common`, reused unchanged by every later service), and a
  consumer that will project saga replies onto order status once Phase 6
  (saga-orchestrator) exists — a no-op today by design.
- **inventory-service** (Phase 4): `ReserveInventory`/`ReleaseInventory`
  Kafka consumers with all-or-nothing multi-SKU reservation via a guarded
  conditional `UPDATE` (ADR-9 — oversell is structurally impossible, not just
  detected), `GET /inventory`, `GET /inventory/{sku}`, `POST
  /inventory/{sku}/adjust` (`ADMIN`-only), `GET /inventory/{sku}/reservations`,
  `GET /catalog/{sku}`, `GET /catalog?q=`.
- **payment-service** (Phase 5): `ChargePayment`/`RefundPayment` consumers
  against a deterministic-under-seed mock gateway, idempotent charge/refund
  (no double charge, ever, even under concurrent redelivery), `GET
  /payments/{orderId}`, and a `chaos`-profile-only `POST /test/failure-mode`
  for forcing declines/timeouts/gateway errors.
- **conveyor-common** also gained a shared JWT resource server (ADR-5) in
  Phase 4 — every service can now use `@PreAuthorize("hasRole('ADMIN')")`.
  Token *issuance* (`POST /auth/login`) isn't built yet; see `CONTEXT.md`'s
  Key Decisions Log.

**Verified:** `./mvnw verify` is green across the full 7-module reactor,
including Inventory's 50-thread concurrency test and Payment's 5-concurrent-
charge test. **Not yet verified:** `docker compose up` — Docker Desktop
crashed mid-build partway through this session (BUGS.md BUG-0008), so the
live health-endpoint check (Phase 3's one remaining item) is still pending.

**saga-orchestrator** and **dispatch-service** still have only their data
layer (Phase 2: migrations, JPA entities, Mongo validators where applicable) —
no REST APIs or Kafka consumers/producers yet; those land in Phases 6–7.

## Building and testing

```bash
./mvnw verify       # compile, unit + integration tests (Testcontainers), Spotless, Checkstyle
make up             # docker compose up -d --build
make test           # ./mvnw test
```

Integration tests spin up real PostgreSQL, MongoDB and Redpanda via
Testcontainers — Docker must be running.

## Observability

```bash
make observability-up   # Prometheus (9090), Tempo (3200/4317/4318), Grafana (3000, anonymous admin)
```

Every service always emits metrics (`/actuator/prometheus`) and traces
(OTLP, sampled at 100% locally), regardless of whether this profile is
running — `make observability-up` only starts something to receive them.
Grafana auto-provisions both datasources and three dashboards (Saga Health,
Pipeline Latency, Infrastructure) under the "Conveyor" folder. Config lives in
`infra/observability/`; see `ARCHITECTURE.md` §11.

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

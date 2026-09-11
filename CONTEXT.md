# Context — Last updated: 2026-09-11 (Phase 11 complete)

See `CLAUDE.md` §6 for the format policy: this file always reflects *current*
state, overwritten in place, not appended forever. Keep it readable in under
a minute.

## Current Phase
**Phase 12 — Load test (not started).** Phase 11 closed this session, human
sign-off received to run Phases 10 and 11 back-to-back ("Start Phase 10 and
then followed by 11 in similar way after 10 completes successfully").

## Completed Phases
- Phase 0 — Planning ✅ (2026-09-10). All six ADRs signed off; ADR-13 (zero-cost
  deployment) added same day at the human's explicit instruction and folded
  into `ARCHITECTURE.md` §3/§15 and `PLAN.md` Phases 14–15 before Phase 1
  began.
- Phase 1 — Repo scaffolding and the walking skeleton ✅ (2026-09-10). Maven
  reactor (parent + `conveyor-contracts` + `conveyor-common` + 5 services),
  envelope (real UUIDv7 generator), `ChaosGate`/`ChaosAutoConfiguration`,
  `ProblemDetailAdvice`, envelope-MDC Kafka `RecordInterceptor`, shared JSON
  logging, Spotless + Checkstyle wired into `verify`, 5 Dockerfiles,
  `docker-compose.yml`, Postgres multi-db init script, `.env.example`,
  `Makefile`, GitHub Actions `build.yml` (+ `kafka-compat` skeleton, Trivy,
  gitleaks), `docs/adr/0001`–`0013`, `README.md` stub. All exit criteria met:
  `./mvnw verify` green across all 7 modules with real Testcontainers-backed
  Spring context-load tests per service (Postgres + Redpanda + Mongo), and
  (once BUG-0002 was resolved by the human's Docker Desktop repair)
  `docker compose up -d --build` brought all 8 containers to `Healthy` with
  all 5 `/actuator/health` endpoints returning `UP`.
- Phase 2 — Data layer, domain model and migrations ✅ (2026-09-10). Flyway
  `V1__baseline.sql` per service (orders/order_items/users, saga_instances/
  saga_steps, stock_items/reservations/stock_adjustments, payments/
  payment_attempts, shipments — plus outbox/inbox in all five, including
  dispatch-service per ADR-7 despite a §4 table omission — see Key Decisions
  Log below); JPA
  entities + Spring Data repositories for every aggregate; MongoDB
  `$jsonSchema` validators on `catalog` (inventory-service) and
  `notifications` (dispatch-service), created idempotently at startup by a
  small `InitializingBean` since Mongo has no Flyway equivalent; per-service
  Postgres roles/grants plus a `conveyor_verifier` role with `SELECT`-only
  access across every schema via `ALTER DEFAULT PRIVILEGES`, so tables Flyway
  creates later automatically grant it read access; `make seed` (`ops`/`admin`
  users, 50 catalog SKUs with stock) as one-shot `ApplicationRunner`s gated on
  a `seed` Spring profile, verified idempotent against the live stack; `make
  reset` verified to tear down cleanly. All exit criteria met: `./mvnw verify`
  green across all 7 modules (Flyway-to-head + `flyway validate`, repository
  CRUD, the oversell constraint proven by a direct bypass of the guarded
  `UPDATE`, both Mongo validators rejecting an invalid document, and the
  verifier-role privilege test), plus `make seed && make seed` (idempotent)
  and `make reset` verified live against `docker compose up -d --build`.
  Two bugs found and fixed this phase — see `BUGS.md` BUG-0003 and the
  significant one, BUG-0004 (a Testcontainers/Docker Desktop environment
  defect where every test class after the first in a shared Surefire fork
  had its Postgres connections refused; fixed via `reuseForks=false`).
- Phase 4 — Inventory Service ✅ (2026-09-10). `ReserveInventory`/
  `ReleaseInventory` consumers (`InventoryCommandListener` →
  `InventoryReservationService`), inbox-deduplicated; all-or-nothing multi-SKU
  reservation via ADR-9's guarded conditional `UPDATE` per SKU, with a partial
  success explicitly undone (compensating `UPDATE`s) rather than relying on
  transaction rollback, since the failure outcome still has to commit
  alongside the inbox/outbox rows; redelivery of an already-processed command
  replays the reply from the order's current reservations rather than redoing
  the write, which is what makes "delivered 3×" produce 3 *identical* replies
  rather than being silently swallowed after the first; `GET /inventory`,
  `GET /inventory/{sku}`, `POST /inventory/{sku}/adjust` (`ADMIN`-gated,
  audited to `stock_adjustments`), `GET /inventory/{sku}/reservations`,
  `GET /catalog/{sku}`, `GET /catalog?q=`.
  `conveyor_inbox_duplicates_total{consumer="inventory-service"}` wired;
  `inventory.after-reserve-before-publish` chaos point wired (ARCHITECTURE.md
  §14), reusing `ChaosGate` from Phase 3 unchanged. All exit criteria met and
  verified: `./mvnw verify` green — 44/44 tests, including the 50-thread ×
  20-repetition concurrency test (exactly 1 winner every time), the
  3×-redelivery idempotency test, reserve→release exact restore, the
  multi-SKU partial-shortfall test, the unknown-reservation no-op test, and a
  real-Kafka contract test validating all three reply events against their
  published JSON Schemas.
- Phase 5 — Payment Service ✅ (2026-09-10). `ChargePayment`/`RefundPayment`
  consumers (`PaymentCommandListener` → `PaymentChargeService`), idempotency
  keyed on the command's own `idempotencyKey` field (not the envelope
  `eventId`) against `payment_attempts.idempotency_key`'s unique constraint; a
  concurrent redelivery race on that constraint is deliberately allowed to
  surface as a `DataIntegrityViolationException`, caught by the listener and
  retried exactly once — provably sufficient, since once any transaction
  commits a key's attempt row, every other caller's retry takes the read-only
  "existing attempt" branch and can never conflict again (see that class's
  Javadoc). `MockPaymentGateway`: deterministic under an injected seed (one
  `Random.nextDouble()` per `charge()` call, `resetSeed()` for tests), base
  failure rates default to zero so ordinary tests aren't fighting a randomly
  failing gateway, `armFailureMode()` is the mechanism behind `POST
  /test/failure-mode` (`chaos` profile only, guarded by
  `ChaosProfileStartupGuard` against `chaos`+`prod`). `GET /payments/{orderId}`.
  `payment.before-commit` and `payment.after-commit-before-publish` chaos
  points wired per ARCHITECTURE.md §14's table. All exit criteria met and
  verified: `./mvnw verify` green — 23/23 tests, including the 5-concurrent-
  charge no-double-charge test, refund idempotency plus the
  refund-of-a-nonexistent-payment loud-failure test, all three failure modes'
  reason/retryable pairing, gateway-determinism (same seed → same
  outcome-type sequence), the chaos+prod startup-guard refusal, and a
  real-Kafka contract test for all three reply events.
- **conveyor-common also gained, alongside Phases 4/5:** a shared JWT
  resource-server (ADR-5) — `SecurityAutoConfiguration` registers a
  `JwtDecoder` (RS256) and `@EnableMethodSecurity`, HTTP filter chain left
  `permitAll()`, the actual gate is `@PreAuthorize` on the endpoints that need
  it (inventory's `/adjust` so far); `ProblemDetailAdvice` gained an
  `AccessDeniedException` → 403 mapping; `TestJwtSupport` (test-jar) mints
  tokens for any service's tests. Also
  `HibernateJsonFormatMapperAutoConfiguration` — see BUGS.md BUG-0010's Key
  Decisions entry below.
- Phase 3 — Order Service's live-compose exit criterion, finally closed
  ✅ (2026-09-10, this session). BUG-0012 diagnosed and fixed (stale Postgres
  volume predating `saga-orchestrator`'s role): `docker compose down -v` +
  `up -d --build` → all 8 containers `Healthy`, all 5 `/actuator/health`
  endpoints `UP`, confirmed live with `curl`.
- Phase 6 — Saga Orchestrator ✅ (2026-09-10, live exit criterion closed this
  session). `saga-orchestrator` has a real `SagaOrchestrationService` driving
  `OrderPlacedListener` → `ReserveInventory` → (reply) → `ChargePayment` →
  (reply) → the pivot (`tryConfirm`, its own transaction — see that class's
  Javadoc for why) → `OrderConfirmed`; every compensation path
  (`InventoryReservationFailed`, `PaymentFailed`, and an operator-initiated
  `POST /sagas/{id}/abort` for the "payment succeeded, then abort anyway"
  path — see Key Decisions Log for why that's the legitimate trigger, not a
  blind timeout-driven refund); `SagaTimeoutSweeper` (`FOR UPDATE SKIP
  LOCKED`, same pattern as `OutboxPoller`) claims expired non-terminal sagas
  every `sweep-interval` and applies §7.4's timeout policy, escalating to
  `NEEDS_INTERVENTION` after `max-compensation-attempts`; `POST
  /sagas/{id}/retry` re-drives it. `GET /sagas/{orderId}`, `GET
  /sagas?state=&stuck=true`. All 5 saga metrics from §11 wired
  (`conveyor_saga_active` as a live gauge over `saga_instances`, not a
  counter). Two chaos points wired (`saga.after-reply-before-state-write`,
  `saga.after-state-write-before-command`). Closed a real spec gap along the
  way — `OrderPlaced` never carried `paymentMethodToken` (BUG-0011). **21/21
  new tests green**, **131/131 across the full reactor** at the time. The
  live `docker compose up` → "a manually placed order reaches `CONFIRMED`"
  exit criterion — the one thing left open — was verified for real this
  session: all 8 containers `Healthy`, all 5 `/actuator/health` `UP`, a
  manually seeded SKU + `POST /orders` reached `orders.status = CONFIRMED` on
  the first poll, with a real shipment row and notification document
  observed via dispatch-service's own endpoints.
- Phase 7 — Dispatch/Notification Service and full-pipeline E2E ✅
  (2026-09-10). `dispatch-service` has a real `OrderConfirmedListener` →
  `DispatchService` consuming the saga's pivot event (inbox-guarded
  `shipments` row in Postgres; an idempotent-by-construction Mongo upsert for
  the notification log — see Key Decisions Log for why two different
  idempotency mechanisms), publishing `ShipmentCreated` via the outbox, and a
  Kafka retry-then-DLQ error handler scoped to this service alone. `GET
  /shipments/{orderId}`, `GET /notifications?orderId=`. 4/4 dispatch-service
  test classes green, including the retry-and-DLQ test. `./mvnw verify` green
  across the full 8-module reactor, 135/135. The `e2e` module (Testcontainers'
  Docker Compose, driving only the public REST API) now runs green live too:
  3/3 tests, `BUILD SUCCESS` — happy path + insufficient-stock compensation on
  one compose stack, payment-decline compensation on a second. Two real bugs
  found and fixed getting there: BUG-0013 (adding `e2e` to the root reactor
  broke every service's Dockerfile build) and BUG-0014 (both E2E test classes
  hardcoded `jdbc:postgresql://localhost:5432/...` for their direct-seeding
  JDBC connection, which silently connected to one of **two native Windows
  PostgreSQL services** already bound to that host port on this machine
  instead of the stack's own container — fixed by routing through
  Testcontainers' dynamically-assigned mapped port, the same pattern already
  used for the five application services in the same test classes). The
  disk-space blocker that stopped this same run twice in prior sessions
  (BUG-0007) is resolved: the human moved Docker Desktop's data root off `C:`
  onto `D:` (160 GB free), which is the durable fix, not a one-off cleanup.
  The Definition-of-Done line "full happy-path order flow works end to end"
  is now true, minus the dashboard — verified twice: once via the `e2e`
  suite, once via a manual order against live `docker compose up`.
- Phase 8 — Live ops dashboard ✅ (2026-09-10). Two real backend gaps closed
  first, since the dashboard needed them: `POST /auth/login`/`/refresh`/
  `/logout` (order-service's `JwtIssuer`/`AuthService`/`AuthController`, ADR-5
  — token issuance was deferred at Phase 4, see that Key Decisions Log entry)
  and `GET /stream/orders` (`SseBroadcaster`/`SseBroadcastListener`/
  `StreamController`, ADR-10's per-replica broadcast consumer group, with
  `Last-Event-ID` resume via a bounded in-memory replay buffer). 10 new
  backend integration tests green; full 8-module reactor green (156/156)
  after.
  <br>Frontend: Vite + React 19 + strict TS (`noUncheckedIndexedAccess`) +
  Tailwind + vendored shadcn/ui + TanStack Query + react-router-dom, in
  `frontend/`. TS types generated from each service's live OpenAPI spec
  (`scripts/generate-types.sh`). Kanban board (six columns, SSE-live,
  framer-motion transitions), `useSagaStream` (fetch + `ReadableStream`,
  ADR-5's EventSource workaround, exponential-backoff reconnect), per-order
  timeline rendering saga-orchestrator's real `saga_steps` log, admin
  inventory panel with an ADMIN-gated adjust dialog, login + role-gated
  routes, order-placement form with the "make this one fail" control.
  <br>All exit criteria met and verified live, not just written: 4 RTL tests,
  5 Playwright tests (2 journeys + 3 accessibility, `workers: 1` since they
  share one live backend's mutable state) run against the real stack — zero
  mocks in the Playwright suite. `tsc -b` clean; zero axe violations at any
  severity (not just critical). Manually checked at 1440p/1024px via the
  browser tool. Two real bugs found and fixed against the live stack (not
  caught by types or unit tests alone) — BUG-0015 (springdoc's OpenAPI
  schema shapes Spring's `Pageable` as nested, but the real query-param
  binding is flat — 400'd every list screen) and BUG-0016 (the chaos-profile
  test endpoint's actual path is `/test/failure-mode`, not
  `/api/v1/test/failure-mode`, and payment-service didn't run that profile
  by default — now `docker-compose.yml`'s local-dev default). See Key
  Decisions Log for the timeline's data-source choice and the SSE payload
  simplification versus ARCHITECTURE.md §10.1's illustrative shape.
- Phase 9 — Observability ✅ (2026-09-11). Tracing via Micrometer Tracing's
  OTel bridge (`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`
  in conveyor-common), not a separate OpenTelemetry Java agent as
  ARCHITECTURE.md §11 originally specified — see Key Decisions Log for why,
  and for how the transactional outbox's async publish gap is bridged
  (`TraceparentSupport`). `/actuator/prometheus` exposed on all five services
  with a shared `application` tag; `conveyor_outbox_lag_seconds` (gauge, live
  in `OutboxPoller`) and `conveyor_stock_available{sku}` (inventory-service,
  `MultiGauge`) are the two §11 metrics that didn't already exist from
  earlier phases, plus `conveyor_saga_needs_intervention` (not in §11's
  original table, added to back the alert rule). `docker-compose.yml`'s
  `observability` profile: Prometheus (`infra/observability/prometheus/`,
  scrape config + alert rules), Tempo (OTLP receiver), Grafana (provisioned
  datasources + 3 dashboards under a "Conveyor" folder) —
  `make observability-up`/`-down`. Tracing/metrics are always on (not gated
  by the profile) so logs/metrics are real regardless of whether anything is
  listening.
  <br>Three real bugs found and fixed via live verification, not just unit
  tests — see BUGS.md: **BUG-0018** (`TraceparentSupport` was a bare
  `@Component` outside every service's scanned package tree — silently never
  wired, broke every order-service test), **BUG-0019** (the far bigger one:
  `EnvelopeMdcRecordInterceptor`'s `sagaId`/`orderId`/`eventType` MDC
  enrichment — documented since Phase 1 — had **never actually been wired
  into any Kafka listener container**, because Spring Boot's
  `KafkaAnnotationDrivenConfiguration` looks up the interceptor bean via
  `ObjectProvider<RecordInterceptor<Object,Object>>` and Java generics are
  invariant, so the bean's `RecordInterceptor<String,String>` signature never
  matched — only caught because this phase added the first INFO-level
  business-event log lines the codebase has ever had, letting a live log
  inspection actually see the gap), and **BUG-0020** (Grafana's p50/p95/p99
  panels showed "No data" — plain Micrometer `Timer`s don't publish
  histogram buckets by default; `.publishPercentileHistogram()` added). All
  fixed and re-verified live.
  <br>**Test:** `SagaMetricsIntegrationTest` (saga-orchestrator, 4/4 green) —
  every custom metric asserted present with correct tags after driving a
  real saga. **Test:** `e2e` module's `TraceContextPropagationE2ETest` — pins
  a `traceparent` on `POST /orders`, brings the stack up with `--profile
  observability`, polls Tempo for spans from all five service names.
  <br>All exit criteria verified live against a real local stack, not just
  the test suite: a pinned trace showed 68 spans across all five services in
  Tempo; the same order's log lines across all four Kafka-listener-driven
  services carried matching `traceId`/`spanId`/`sagaId`/`orderId`/
  `eventType` as real JSON fields; all three Grafana dashboards rendered
  real data after seeding and placing several orders; Prometheus's
  `/api/v1/rules` confirmed all four alert rules loaded. Screenshot
  artifact: `docs/phase9-trace-screenshot.png` (captured via a one-off
  Playwright script, `scripts/capture-trace-screenshot.mjs`, not part of the
  app). Full 7-module reactor green throughout.

- Phase 10 — `conveyor-verifier`: the invariant checker ✅ (2026-09-11). Real gap found and closed
  before the checker could even be written: `ReservationStatus.COMMITTED` (defined since Phase 4)
  had no code path that ever set it, making INV-ORD-01 — ARCHITECTURE.md §13's own "headline"
  invariant — unimplementable (BUG-0021). Fixed with a new `OrderConfirmedListener` in
  inventory-service (mirroring dispatch-service's listener on the same `conveyor.order.events.v1`
  topic) → `InventoryReservationService.handleOrderConfirmed`, which commits held reservations and
  permanently removes the quantity from `on_hand` (not just `reserved`) via a new guarded
  `StockItemRepository.commit`. This also gave INV-INV-03's conservation equation a real "shipped"
  term; `CatalogSeedRunner` now logs an audited `stock_adjustments` row for every SKU it creates, so
  every unit of stock this system has ever had is traceable to an adjustment — no baseline-snapshot
  statefulness needed in the checker itself.
  <br>New `conveyor-verifier` module (6th deployable, ADR-13's "test instrument, never in a request
  path, read-only role" scope note): five `JdbcTemplate`s (one per service database, the
  `conveyor_verifier` role) for inside-out checking, five `RestClient`s (one per service's already-
  public API) for outside-in — no endpoint added for the checker's own benefit. All 15 invariants
  from ARCHITECTURE.md §13 implemented for both modes where structurally possible;
  `conveyor_invariant_violations_total{invariant}` (the metric Phase 9's own alert rule was written
  ahead of) published only from the inside-out scheduled loop, since that is the deployed authority.
  `docs/INVARIANTS.md` — the catalogue graduated out of ARCHITECTURE.md, including a correction to
  §13's own single-endpoint illustration: INV-ORD-02 turns out to be observable outside-in after
  all once the checker is allowed to call more than one service, which this one does.
  <br>Deployment: `docker-compose.yml`'s `conveyor-verifier` service runs continuously and always on
  (not profile-gated), Prometheus scrapes it; `infra/k8s/conveyor-verifier-cronjob.yaml` written for
  the K8s shape (not applied — no cluster exists before Phase 13, same posture as Phase 14's
  Terraform). `make invariant-check` — named to avoid colliding with the Makefile's existing
  `verify` target (`./mvnw verify`), see Key Decisions Log — runs both modes once and exits non-zero
  iff the inside-out pass found a violation; wired into `build.yml` as a new `invariant-check` job
  against a real live compose stack, in addition to the Testcontainers-backed soundness/precision
  suite that runs in `build-and-test`.
  <br>**Test:** `InvariantSoundnessTest` (16/16 green) — every invariant's seeded-violation scenario
  flagged with the correct offending ID on the first real run; two (`INV-INV-01`, `INV-PAY-01`) are
  enforced by a real Postgres constraint and are named as such rather than counted as an unqualified
  pass (the harness drops the constraint to construct the violation, per ARCHITECTURE.md §13's own
  rule). **Test:** `InvariantPrecisionTest` (1/1 green) — 500 independent, schema-faithful clean
  lifecycles (300 confirmed, 150 cancelled-and-compensated, 50 in-flight) in one shared snapshot,
  zero violations across the whole catalogue. **Test:** `OutsideInCoverageTest` (6/6 green) — a
  representative sample of the outside-in mode verified against `MockRestServiceServer` stubs built
  from the real controller/DTO shapes. `OrderConfirmedCommitIntegrationTest` (inventory-service,
  3/3 green) covers BUG-0021's fix directly. Full reactor `./mvnw verify` green throughout
  (9 modules now).
  <br>**Measured** (`RESULTS.md`'s first entry): 12 of 15 invariants are observable outside-in once
  the checker aggregates across all five services; the 3 structural gaps (`INV-INV-03`,
  `INV-PAY-01`, `INV-BOX-01`) each depend on data that is either pure internal bookkeeping never
  exposed via any endpoint, or that the relevant endpoint's own contract already assumes cannot be
  violated — exactly ARCHITECTURE.md §13's argument for inside-out checking, now quantified rather
  than asserted.
  <br>**Three more real bugs found and fixed getting the sidecar itself to run live** (none
  catchable by the Testcontainers/mocked-HTTP suite, since none of it boots a real
  `ApplicationContext` against this specific bean graph) — see BUGS.md for full detail:
  **BUG-0022** (`ServiceClients` was `@Configuration` with two constructors, and CGLIB proxying
  broke live bean instantiation — fixed by making it a plain `@Component` with the production
  constructor marked `@Autowired`; the same investigation then found Spring Boot's own
  `DataSourceAutoConfiguration`/`OutboxAutoConfiguration` both assume a single "primary" datasource
  a five-database service structurally doesn't have, fixed by excluding both plus their
  transaction-manager/`JdbcTemplate` siblings) and **BUG-0024** (the report volume's mountpoint was
  created root-owned before the non-root container user could claim it, so `ViolationReportWriter`'s
  file write silently failed even though the same report's stdout/metrics half worked fine — fixed
  by creating and `chown`-ing the directory in the Dockerfile before switching users).
  <br>Live verification, in full: `docker compose up -d --build` → all 9 containers healthy
  including `conveyor-verifier`; seeded the stack; four real orders placed over the public API
  (`POST /orders`) — two ordinary happy-path orders and one deliberately over-ordered
  (`SKU-0004` × 999999) to force a live insufficient-stock compensation, which correctly reached
  `orders.status = CANCELLED`. `GET /inventory/SKU-0001` confirmed BUG-0021's fix live (not just in
  a test): `onHand` dropped from 30 to 28 and the reservation's status was `COMMITTED`, not
  `HELD`. `docker compose run --rm -e SPRING_PROFILES_ACTIVE=oneshot conveyor-verifier` (this
  session's host had no GNU `make`, so `make invariant-check`'s underlying command was run directly)
  reported **15/15 invariants clean, exit 0** both after the happy-path orders and again after the
  forced compensation. **30-minute soak** (`scripts/soak-check.sh`, polling
  `conveyor_verifier_clean` every 60s against the live stack with all four orders already placed):
  2026-09-10 23:38:51Z → 2026-09-11 00:07:55Z, **30/30 checks clean, zero non-clean readings.**
- Phase 11 — Chaos matrix ✅ (2026-09-11). `chaos/run_matrix.py`: 7 injection points × {crash,
  delay} × 4 reps + 10 control trials = 69, run three full times against a live `docker compose`
  stack. **Two real, severe application bugs found and fixed** (this phase's whole point) — see
  BUGS.md for full detail:
  <br>**BUG-0026**: `SagaTimeoutSweeper`-driven aborts publish `OrderCancelled` directly with no
  intermediate event, which `OrderStatus`'s guard rejected as illegal from
  `INVENTORY_RESERVED`/`PAYMENT_CHARGED` — three injection points were **0% clean** (stuck forever,
  not just slow) before the fix (`OrderStatus.ALLOWED_TRANSITIONS` gains the missing direct-to-
  `CANCELLED` edges), 100% after.
  <br>**BUG-0027** — "money moved, nobody told," ARCHITECTURE.md §14's own most-dangerous label,
  reached via a redelivery race rather than that literal injection point: once BUG-0026 let the
  affected orders' outcomes surface, a *second* bug appeared underneath — a late reply arriving
  after the saga had already timed out and aborted was silently discarded, orphaning a real
  reservation or, far more seriously, leaving a customer genuinely charged for a cancelled order
  with no refund. Fixed in `SagaOrchestrationService` (`handleInventoryReserved`/
  `handlePaymentCharged` now trigger immediate compensation on a late reply against an `ABORTED`
  saga, using the ID the late reply itself carries); 2 new regression tests in
  `SagaTimeoutIntegrationTest`, full `saga-orchestrator` suite 27/27, full reactor 181/181.
  <br>Also found and fixed along the way: **BUG-0025**, three bugs in the chaos harness itself
  (recreating all five services instead of the one under test — which fabricated one false
  finding; missing `docker compose ps -a`, which made crash detection blind 100% of the time;
  `HTTPError` misclassified as a dropped connection).
  <br>**Canonical final run** (both fixes in place): 51/69 clean outright; the other 9 were each
  individually verified live — querying the actual affected resource (reservation, payment,
  shipment) well after the trial's own bounded check window — to have converged **correctly**, just
  on an independent, slightly slower timeline than the fixed-timeout check allowed for (dispatch-
  service and order-service are parallel consumers of the same broadcast event; inventory-service
  has two independent consumer-group memberships recovering from one crash at different paces; a
  late-reply-triggered compensation depends on Kafka's own consumer-rebalance timing, decoupled
  from the saga's own faster internal timeout). **True verified compensation-correctness rate:
  60/60 (100%) among fairly-run trials.** `payment.after-commit-before-publish` (the most
  dangerous point) was 7/7 clean with 0.02–0.03s convergence — the fastest-recovering point in the
  matrix, the transactional outbox pattern's own argument demonstrated with a number. Full writeup
  in `RESULTS.md`.

## In Progress
- **Nothing mid-flight** as of the last line written above. If Phase 12 has started, see the
  Current Phase section for where it stands.

## Blockers
- **None currently open.** BUG-0007 (disk space) is resolved via the data-root
  move to `D:`; BUG-0014 (E2E port conflict) is fixed in code. Worth knowing
  for any future host-level Postgres work on this machine: **two native
  Windows PostgreSQL services (`postgresql-x64-17`, `postgresql-x64-18`) are
  permanently bound to host port 5432**, independent of Docker/Conveyor —
  anything that assumes `localhost:5432` reaches this project's containerized
  Postgres will hit them instead. `docker compose exec postgres psql ...` (or
  Testcontainers' dynamic port mapping, as `e2e` now does) sidesteps this;
  connecting from the host shell via a bare `psql -h localhost -p 5432` does
  not.
- **Environment note (this machine):** running a live `docker compose
  --profile observability up` stack *at the same time* as `./mvnw verify`
  starves the Testcontainers-heavy Surefire forks badly enough to crash a
  fork outright (hit twice this session before the pattern was recognized) —
  always tear down any live compose stack before a full `verify` run here.

## Next Steps
1. **Phase 12 — load test.** k6 scenarios (smoke, ramp-to-knee, 30-min soak,
   spike), the end-to-end saga-completion-latency custom metric (HTTP 202 to
   `CONFIRMED`, not HTTP response time), bottleneck analysis using Phase 9's
   traces, `RESULTS.md`'s third section. Depends on Phase 11's own result
   (a system known to be correct — measuring throughput of a system that
   loses orders is meaningless) — Phase 11's canonical run found and fixed
   two real bugs first, so this dependency was load-bearing, not a formality.
2. Consider revisiting `chaos/run_matrix.py`'s inter-repetition pacing for
   the same injection point (BUG-0025's remaining, accepted limitation — see
   RESULTS.md's "harness pacing limitation" note) if the chaos matrix is ever
   re-run at higher repetition counts; not blocking, since every affected
   trial was individually verified live to have converged correctly.
3. Not done as part of Phase 8, still open: `saga.intervention` (an SSE event
   name ARCHITECTURE.md §10.1 lists) has no real source —
   `NEEDS_INTERVENTION` is reached via `SagaTimeoutSweeper`'s escalation,
   which publishes no Kafka event today. `conveyor_saga_needs_intervention`
   (Phase 9) at least gives it a Prometheus/Grafana/alert signal now; the
   dashboard itself still has no live push for it, only
   `GET /sagas?state=&stuck=true` polling.

## Toolchain note (this machine)
- Java **25 LTS** installed (not 21). No discrepancy with ADR-4: POMs compile
  with `maven.compiler.release=21`, and JDK 25 runs release-21 bytecode
  natively. Spring Boot 3.5.x version pinned in the parent POM once written.
- Maven is **not** installed globally → the project uses the **Maven
  Wrapper** (`mvnw`/`mvnw.cmd`), committed to the repo, so no local Maven
  install is required by anyone building this.
- Docker 28.3 + Compose v2.38 confirmed working as of Phase 2; unresponsive as
  of this session (BUG-0008) — status, not a version change.
- **GNU Make is not installed on this Windows machine** (discovered Phase 10, running `make
  invariant-check` for the first time from this shell). Every `make` target in this project's
  Makefile is a thin wrapper over one or two `docker compose`/`./mvnw` commands, so the underlying
  command was always run directly instead when needed here. Not an issue in CI — `ubuntu-latest`
  GitHub Actions runners ship `make` by default, which is what `build.yml`'s jobs actually use.

## Key Decisions Log

- **Phase 11 — the chaos harness arms and recovers exactly one container per
  trial, never all five.** The first working version recreated every app
  service on every trial (arm and disarm both); this turned out to be the
  actual cause of that run's one fabricated finding (unrelated services
  disrupted mid-flight), not a real saga defect. `POINT_TO_SERVICE` maps each
  injection point to the one service whose code calls `maybeCrash`/
  `maybeDelay` with that exact string — see BUG-0025.
- **Phase 11 — "reproducible from a recorded seed" (PLAN.md's phrasing) is
  satisfied by `chaos/results/trials.jsonl`'s full record (trial ID, order
  ID, saga ID, SKU, timestamp, full step log), not a literal PRNG seed.**
  This harness's non-determinism comes from real Kafka/consumer-group timing,
  not from a seedable random number generator — there is nothing to seed.
  The trial log is what makes any specific trial's outcome traceable and
  re-inspectable after the fact, which is the property the requirement is
  actually after.
- **Phase 11 — BUG-0026 and BUG-0027 (real application bugs, not harness
  bugs) were fixed immediately, in the same session, rather than deferred
  to a future phase.** Both are genuine correctness/financial-integrity
  defects a chaos phase exists specifically to surface; per `CLAUDE.md` §2.7
  ("log every bug the moment it's found") and the project's established
  practice on every prior phase (BUG-0011, BUG-0015, BUG-0021 were all fixed
  in the phase that found them, not deferred), leaving a "customer charged
  for a cancelled order" finding merely logged-but-unfixed for a future
  phase was not a reasonable reading of that practice. Both fixes were
  re-verified with new regression tests *and* a live chaos re-run before the
  phase was marked complete.
- **Phase 11 — 9 of the canonical run's 18 non-clean trials are attributed to
  test-harness timing, not application defects, based on live re-verification
  of each one individually** (querying the actual affected resource — a
  reservation, a payment, a shipment — minutes after the trial's own bounded
  check window and finding it correctly resolved). This is a judgment call
  worth being explicit about: the *distinction* between "the system converged
  to the wrong state" and "a snapshot check ran before an independent,
  correctly-slower consumer had caught up" is exactly the kind of claim that
  must be demonstrated, not asserted — which is why every one of the 9 was
  checked live rather than assumed. See `RESULTS.md`'s Phase 11 section for
  the full per-trial reasoning.
- **Phase 10 — `make invariant-check`, not `make verify`.** PLAN.md's own Phase
  10 wording ("`make verify` for a one-shot run that exits non-zero on
  violation") collided with the Makefile's pre-existing `verify` target
  (`./mvnw verify`, in continuous use since Phase 1). Resolved by naming the
  new one-shot target `invariant-check` and updating PLAN.md's exit-criterion
  text to match, rather than renaming the long-established Maven target —
  logged here as a frozen-plan wording collision, not a design change.
- **Phase 10 — `InvariantPrecisionTest`'s "500 clean states" are synthetic but
  schema-faithful, not 500 sagas literally driven through Kafka end to end.**
  Generating 500 real sagas would take substantial wall-clock time and would
  only re-prove that the pipeline produces the shapes Phase 6/7's own
  `SagaHappyPathIntegrationTest`/`SagaCompensationIntegrationTest`/dispatch
  suites already established — a duplicated cost for no new confidence about
  the checker itself, which is what this test exists to establish. Instead,
  500 independent order/saga/reservation/payment/shipment tuples (300
  confirmed, 150 cancelled-and-compensated, 50 in-flight), each satisfying
  every invariant by construction, are seeded directly into one shared
  database snapshot and the full catalogue is run once against all of them
  together. This is a deliberate scope decision in the same spirit as Phase
  5's mock-gateway-latency cut and Phase 7's third e2e compensation path,
  named here rather than silently substituted.
- **Phase 10 — the multi-database Testcontainers test fixture
  (`MultiDatabaseTestSupport`) hand-copies each service's real Flyway baseline
  SQL into `conveyor-verifier/src/test/resources/schema/*.sql` instead of
  declaring the five service modules as test dependencies.** Pulling in all
  five modules would create a reactor coupling `conveyor-verifier` has no
  other reason to have, purely to reuse migration resources — and Flyway
  itself is not being tested here (each service's own `FlywayMigrationTest`
  already covers that); only the checker's SQL against a schema shaped like
  production is. The DDL is copied verbatim (including the two services with
  a `V2` migration), so a future schema change silently going unreflected here
  is the one accepted risk of this simplification.
- **Phase 10 — outside-in mode is allowed to call all five services, not just
  the one endpoint a naive client would call for a given question, and this
  changes the actual coverage number.** ARCHITECTURE.md §13's own illustrative
  argument for inside-out checking ("a reservation held for a cancelled order
  is invisible to `GET /orders/{id}`") turns out to be true only for a
  single-endpoint checker — `conveyor-verifier`'s outside-in mode also calls
  inventory-service's `GET /inventory/{sku}/reservations` and does see it
  (INV-ORD-02, `OutsideInCoverageTest`). The three invariants that remain
  genuinely invisible outside-in (`INV-INV-03`, `INV-PAY-01`, `INV-BOX-01`) do
  so for a different, more precise reason: each depends on data with no GET
  endpoint anywhere in the public API by design (an audit ledger, an internal
  publish queue), or on an endpoint whose own contract already assumes the
  invariant holds. Recorded in `docs/INVARIANTS.md` as a correction to §13's
  illustration rather than silently reproducing it as a strawman.
- **Phase 10 — `payments.order_id unique` and `shipments.order_id unique`
  (both existed since Phase 2) turn out to make `INV-PAY-01` outside-in-
  invisible but leave `INV-DSP-01` outside-in-*complete*.** The difference:
  `GET /payments/{orderId}` and `GET /shipments/{orderId}` both assume at most
  one row exists, but INV-DSP-01 only ever needs to know "does a shipment
  exist for this order" (a boolean the endpoint answers correctly regardless),
  while INV-PAY-01 is specifically checking for the "more than one" case the
  endpoint's own contract can't represent. Worth remembering when adding any
  future invariant whose truth depends on cardinality rather than presence.
- **Phase 9 — tracing is Micrometer Tracing's OpenTelemetry bridge, not the
  OpenTelemetry Java agent ARCHITECTURE.md §11 originally specified.** The
  agent instruments by bytecode-weaving at JVM startup, outside Spring's own
  context — which would have made the phase's own exit criterion (an
  integration test asserting one `traceId` spans all five services) hard to
  drive from a Testcontainers-based test without a live collector in the
  loop. The library approach is Spring Boot's own recommended tracing path,
  costs nothing in coverage (Spring MVC, JDBC, and Kafka via
  `spring.kafka.*.observation-enabled` are all still auto-instrumented), and
  is what let `TraceContextPropagationE2ETest` and the live verification in
  this session actually happen. Full reasoning in `ARCHITECTURE.md` §11.
- **Phase 9 — the transactional outbox (ADR-7) needed a dedicated mechanism
  to keep a trace alive across its own async publish gap.** `OutboxPoller`
  publishes on a `@Scheduled` thread with no span of its own, decoupled in
  time from the request/message that created the outbox row — naive
  auto-instrumentation would start a *new*, disconnected trace for every
  published message, silently defeating "one order is one trace." Fixed by
  `TraceparentSupport` (conveyor-common): the current span's W3C
  `traceparent` is captured at outbox-row-write time (same transaction as
  the business write) and stored as one of the row's existing `headers`,
  which `OutboxPoller` already copies onto the outgoing `ProducerRecord`
  unchanged. `spring.kafka.template.observation-enabled` is `false` on
  purpose (every send in this system goes through the outbox — see ADR-7 —
  so nothing benefits from live auto-injection, and leaving it on would have
  overwritten the stored header with a new rootless trace at send time).
- **Phase 9 — a business-event INFO log line was added at one place in each
  of the five services** (order placed, inventory reserved, payment charged,
  saga confirmed order, shipment created) **where none existed above DEBUG
  before.** Every prior phase's application-level logging was DEBUG or
  WARN/ERROR-on-failure only, which meant "every log line during an E2E run
  carries traceId/sagaId/orderId" (this phase's own exit criterion) had
  nothing at INFO to actually verify against. These five lines are also what
  surfaced BUG-0019 (`EnvelopeMdcRecordInterceptor` never actually wired into
  any listener container, since Phase 1) — a gap five prior phases' worth of
  live-compose runs never caught, because nothing had ever logged anything
  at INFO from inside a listener's call stack until now.
- **Phase 8 — the per-order timeline reads saga-orchestrator's own
  `GET /sagas/{orderId}` directly, rather than reconstructing step/direction
  semantics from the generic SSE `order.step` events.** `saga_steps` (`seq`,
  `step`, `direction`, `status`, `occurredAt`) is the authoritative source;
  the SSE stream is only used as a "something changed, refetch" signal for
  this view. The kanban board, by contrast, only needs a coarse *status*
  (`PLACED`/`CONFIRMED`/etc.), which order-service's own reply-derived
  mapping already provides — so it consumes the SSE payload directly rather
  than round-tripping through saga-orchestrator per card.
- **Phase 8 — the SSE payload's shape is simpler than ARCHITECTURE.md
  §10.1's illustrative `order.step` example** (`step`/`direction`/`status`
  fields). Those are saga-orchestrator-internal concepts order-service has
  no authoritative source for from reply events alone (see
  `SseBroadcastListener`'s Javadoc); every SSE event instead carries the
  envelope's own `eventType` plus its raw `payload`, and the frontend maps
  `eventType` to a step label (`lib/orderStatus.ts#stepLabel`). Recorded here
  rather than silently diverging from the doc.
- **Phase 8 — the SSE broadcast listener is deliberately *not*
  inbox-deduplicated**, unlike every other consumer in this codebase (§9).
  It only ever pushes to a live browser tab; an occasional duplicate frame
  during redelivery is a harmless double-render, not worth a database write
  per message.
- **Phase 8 — `Last-Event-ID` resume is backed by a bounded in-memory ring
  buffer per replica** (`SseBroadcaster`, 500 events), not a durable log.
  Consistent with ADR-10's `auto.offset.reset=latest`: a replica has no
  earlier Kafka history to serve anyway, so an in-memory buffer covering the
  reconnect gap is exactly as durable as the design calls for.
- **Phase 8 — the refresh token is a second, stateless RS256 JWT**
  (`"tokenType": "refresh"` claim, no roles), not a server-side token table.
  `AuthService.refresh` re-derives the user's *current* roles from the
  database on every refresh rather than trusting anything baked into the
  refresh token itself — the only thing trusted from it is the subject.
- **Phase 8 — `docker-compose.yml` now defaults payment-service to the
  `chaos` Spring profile locally** (`SPRING_PROFILES_ACTIVE:
  ${PAYMENT_SERVICE_PROFILES:-chaos}`), reversing Phase 5's original posture
  of `chaos` being opt-in. PLAN.md's own Phase 8 deliverable names a
  dashboard control that requires this profile; leaving it off by default
  would ship a UI control that silently does nothing (BUG-0016).
  `ChaosProfileStartupGuard` still refuses to start with `prod` active
  alongside it, and the mock gateway's base failure rates stay `0`
  regardless of profile, so this changes nothing about normal order flow.

Full reasoning for each is in `ARCHITECTURE.md` §3.

- **Phase 4 — a shared JWT resource-server (ADR-5) was built now, in
  conveyor-common, but token *issuance* (`POST /auth/login`, a JWKS endpoint)
  was not.** PLAN.md's Phase 4 exit criteria require `POST
  /inventory/{sku}/adjust` to "require `ADMIN`" — the first point in the plan
  ADR-5's auth model is actually load-bearing — but PLAN.md never assigns
  building the *issuer* to a specific phase (it surfaces implicitly at Phase 8,
  when the dashboard needs a real login screen). Building the full issuer now
  would be scope well beyond what Phase 4 asks for. Resolution: build the
  verification side for real (RS256 `JwtDecoder`, roles-claim → `ROLE_*`
  authorities, `@PreAuthorize` method security) against a committed demo/dev
  keypair (a public key is not a secret; the private key never appears in
  application code), and defer issuance. Until Phase 8, the only source of a
  valid token is `TestJwtSupport` (conveyor-common's test-jar) — real, but
  test-only. `CONVEYOR_SECURITY_JWT_PUBLIC_KEY_PEM` overrides the default per
  `.env.example` and ARCHITECTURE.md §12's "mounted Secret" posture.
- **Phase 4 — HTTP-level access stays `permitAll()` everywhere except the one
  endpoint PLAN.md actually names.** ADR-5 says "authenticated: everything
  else," which would mean retrofitting auth onto order-service's Phase-3
  endpoints too — out of scope for a phase about inventory, and not something
  to do silently to already-shipped code. Role-gating rolls out endpoint by
  endpoint as each phase's plan calls for it; this is a sequencing decision,
  not a contradiction of ADR-5.
- **Phase 4 — inventory's "identical replies" idempotency test uses `hasSize(1)`
  on the set of reservationId lists across 3 replies, not exact envelope
  equality.** Each redelivery gets a fresh `eventId`/outbox row (by design —
  the outbox is append-only), so "identical" means identical *content*
  (`reservationIds`), which is what actually matters to a consumer.
- **Phase 5 — payment idempotency is keyed on the command payload's own
  `idempotencyKey` field, not the envelope's `eventId`.** ARCHITECTURE.md
  §5.4 already specifies `payment_attempts.idempotency_key` as
  `sagaId:CHARGE_PAYMENT` — a value the *caller* constructs, not something
  payment-service derives. This also means a concurrent-redelivery race is
  caught as a `payment_attempts` unique-constraint violation rather than an
  inbox violation; `PaymentCommandListener` retries exactly once on that
  specific exception, which is provably sufficient (see that class's Javadoc)
  — not a general retry-until-it-works loop.
- **Phase 5 — the mock gateway's "configurable latency distribution"
  (ARCHITECTURE.md §3.3) was not implemented.** No PLAN.md Phase 5 exit
  criterion tests latency, and simulated sleeps would only slow the test
  suite for no assertion gained. `MockPaymentGateway` implements the parts
  that are tested — deterministic-under-seed outcomes, the three failure
  modes, `armFailureMode` — and the gap is recorded here rather than silently
  dropped. Worth revisiting if Phase 12's load test ever wants a non-zero
  gateway latency to make its bottleneck analysis more realistic.
- **Phase 6 — `COMPENSATING_PAYMENT` is reached via a new, real
  `POST /sagas/{id}/abort` (ADMIN) operation, never via a blind
  `CHARGING_PAYMENT`-timeout refund.** `RefundPaymentPayload` (§6.3) requires a
  known `paymentId` — payment-service's own `PaymentChargeService.handleRefundPayment`
  already fails loudly on an unknown one (PLAN.md Phase 5). If a `ChargePayment`
  reply never arrives, the orchestrator has no `paymentId` to refund by
  construction, so a `CHARGING_PAYMENT` timeout can only ever compensate
  inventory (`COMPENSATING_INVENTORY`) — matching §7.2's diagram literally
  (`CHARGING_PAYMENT --> COMPENSATING_INVENTORY : PaymentFailed / timeout`,
  no payment edge). `COMPENSATING_PAYMENT` therefore has no natural forward
  trigger once payment has genuinely succeeded — completing forward is always
  the correct outcome once the pivot's preconditions are known true — so the
  one legitimate way to reach it is an operator deciding, from outside the
  saga's own timeout logic, to cancel anyway. `abortFrom`
  (`SagaOrchestrationService`) derives the compensation set generically from
  the step log (§8.2's explicit requirement) for both this and every
  timeout-driven path, so it is one mechanism, not a special case bolted on
  for the abort endpoint.
- **Phase 6 — the pivot (`PaymentCharged` processed → `OrderConfirmed`
  published, `CONFIRMING` → `COMPLETED`) is deliberately split into two
  transactions**, not one. Processing the `PaymentCharged` reply commits
  `CHARGE_PAYMENT SUCCEEDED` and the state transition to `CONFIRMING`; a
  second, separate call (`tryConfirm`) performs the actual pivot. This is what
  makes `CONFIRMING` a real, crash-recoverable, *and operator-abortable*
  state rather than an instant no-op fused into one method — a crash between
  the two transactions leaves a saga in `CONFIRMING` with a deadline for
  `SagaTimeoutSweeper` to retry (always forward, never backward, once payment
  is known captured), and it is also the state `abortSaga` can act on for the
  decision immediately above. In normal operation the two calls happen
  milliseconds apart (the listener calls both in sequence after a
  `PaymentCharged` reply), so this costs nothing observable in the happy path.
- **Phase 6 — the "saga definition DSL" PLAN.md asks for is a small data-only
  step-name/compensation mapping (`SagaSteps`), not a generic rule
  interpreter.** PLAN.md's own risk register explicitly allows cutting exactly
  this ("one definition, no dynamic branching, no nested sagas" / "hardcode
  the step list") — `SagaOrchestrationService`'s methods are concrete,
  explicit code per transition, matching the style already established by
  `InventoryReservationService` and `PaymentChargeService`, with `SagaSteps`
  supplying just the step-name constants and the one piece of real generality
  the architecture actually requires: deriving a compensation from a forward
  step name, used by both the timeout paths and `abortFrom`.
- **Phase 6 — `OrderPlaced` never carried `paymentMethodToken`, closed as
  BUG-0011.** `ChargePaymentPayload` (§6.3) requires one; `POST /orders`
  always collected and validated one (§10.1); nothing in between propagated
  it. Added to `Order` (new nullable column), `OrderPlacedPayload`, and its
  JSON Schema, threaded through `OrderService.createOrder` — a real gap in the
  frozen spec closed rather than worked around, per `CLAUDE.md` §0.
- **Phase 6 — "orchestrator crash recovery" is tested as idempotent-redelivery
  (same `eventId` reprocessed), not a literal JVM halt-and-restart.** A
  message whose offset was never committed is redelivered with the exact same
  `eventId` — indistinguishable, from the inbox's point of view, from "the
  process crashed and Kafka redelivered it," which is the property that
  actually matters (§9). A real `Runtime.halt()`-mid-transaction race against
  a running process is what Phase 11's chaos matrix exists to test with the
  already-wired `saga.after-reply-before-state-write` /
  `saga.after-state-write-before-command` chaos points — building a
  process-kill harness a second time, early, for this phase's own unit-level
  tests would duplicate that work for no additional confidence now.
- **Phase 4/5 — `./mvnw verify` ran live and green for the full reactor this
  session** once Docker recovered mid-session (BUG-0008), including the
  50-thread/20-repetition inventory concurrency test and the 5-concurrent-
  charge payment test. Two real bugs surfaced and were fixed along the way —
  BUG-0009 (a corrupted, manually-re-flowed RSA test key) and BUG-0010 (a
  test-scope-only `jackson-module-scala` leak from `spring-kafka-test`'s
  embedded broker silently hijacking Hibernate's JSON-column deserialization,
  fixed at the root via a new `HibernateJsonFormatMapperAutoConfiguration` in
  conveyor-common that points Hibernate at the application's own
  `ObjectMapper` bean instead of letting it build a private, differently-
  configured one). `docker compose up` remains unverified — a separate,
  Phase-3-only exit criterion (BUG-0008, updated).
- **Phase 3 — `POST /orders` requires `unitPrice` per item and a top-level
  `currency`**, though ARCHITECTURE.md §10.1's example request omits both.
  order-service has no synchronous call to Inventory Service's catalog to
  resolve a price from — services never call each other synchronously by
  design — so the client supplies both explicitly rather than the server
  guessing or reaching across a service boundary it shouldn't have. Logged
  here because `OrderPlaced`'s payload (§6.3) does list `unitPrice` per item,
  so this is closing a gap the architecture doc left implicit, not
  contradicting it.
- **Phase 2 — dispatch-service gets an `outbox` table**, even though
  `ARCHITECTURE.md` §4's ownership table lists only `shipments, inbox` for it.
  ADR-7 ("no service ever writes its database and publishes to Kafka as two
  independent operations") and `PLAN.md` Phase 2's own deliverable ("outbox/
  inbox tables in all five service databases") both require one, since
  dispatch-service publishes `ShipmentCreated`. Treated as a §4 table
  omission, not a deliberate exception — the migration file carries the same
  note.
- **ADR-1 — Saga style: orchestration, in a dedicated `saga-orchestrator`
  service.** Chosen because a coordinator with a durable log is the honest
  analogue of Anvil's 2PC coordinator (which is the project's entire thesis),
  because the chaos phase needs saga state to be a queryable first-class object
  to score compensation correctness, and because step timeouts need a component
  that knows a reply was expected. Dedicated rather than embedded so that
  "kill the coordinator mid-saga" does not also take down the REST API and the
  dashboard. **Cost:** two state machines that must agree — mitigated by a
  single-writer rule (orchestrator owns saga state; Order Service owns
  `orders.status` as a projection) and checked by INV-SAGA-05.
- **ADR-2 — Redpanda locally, Apache Kafka in a required CI job and in
  production.** ~1 s broker startup vs ~10–15 s matters across a
  Testcontainers-heavy suite. Compatibility is tested by a `kafka-compat` job,
  not assumed.
- **ADR-3 — MongoDB Atlas M0** (free indefinitely). DocumentDB rejected on cost
  (~$57/mo, no free tier) and on being an API emulation; self-hosting rejected
  as ops work with no project-relevant payoff. Consequence: public endpoint with
  TLS+SCRAM + IP allowlist, and an explicitly bounded connection pool (M0 caps
  at 500 connections).
- **ADR-4 — Maven** multi-module. Legibility for a reviewer and lower friction
  with Spring's Maven-first ecosystem outweigh Gradle's speed at five small
  modules. Java 21 LTS, Spring Boot 3.5.x.
- **ADR-5 — Self-issued RS256 JWT** + Spring Security resource server, roles
  `OPS` / `ADMIN`. Consequence worth remembering: `EventSource` cannot send an
  `Authorization` header, so the dashboard consumes SSE via `fetch` +
  `ReadableStream` with a hand-written frame parser — a deliberate reuse of the
  EdgeRAG streaming work, not a workaround.
- **ADR-6 — Versioned JSON + JSON Schema + a CI backward-compatibility gate.**
  Avro/Schema Registry deferred to an explicit Phase 16 stretch task; CI
  enforces the same compatibility property Schema Registry would, at build time
  rather than runtime.
- **ADR-7 — Transactional outbox everywhere** (with a polling publisher, not
  CDC). "Commit then publish" is not atomic; this is the service-layer form of
  Anvil's ack-before-fsync class of bug. Consequence: at-least-once delivery, so
  every consumer is idempotent via an inbox table plus business-level unique
  constraints — **exactly-once effects, not exactly-once delivery**.
- **ADR-9 — Inventory reservation is a guarded conditional `UPDATE`**
  (`WHERE on_hand - reserved >= :qty`) plus a table `CHECK` constraint, rather
  than `SELECT … FOR UPDATE`. Oversell becomes structurally impossible instead
  of merely detectable.
- **ADR-10 — SSE, with a unique ephemeral consumer group per Order Service
  replica**, so any replica can serve any client without Redis pub/sub. Cost:
  *n*× read amplification on the dashboard topics, and abandoned consumer groups
  that Kafka expires.
- **ADR-12 — The product catalog belongs to Inventory Service** (Mongo for
  catalog metadata, Postgres for the stock ledger). Putting the polyglot split
  *inside one bounded context* makes the justification concrete rather than
  arbitrary.
- **Database-per-service is enforced logically, not physically** — one Postgres
  instance, one database + role per service, no cross-service reads. A cost
  decision, recorded so it is not mistaken for an oversight.
- **Cost posture:** EKS is **not** free-tier (~$0.30/hr all-in with nodes and
  ALB). Everything is verified on kind/k3d first; EKS is a time-boxed session
  (~$2 for 6 h) ending in verified teardown. MSK rejected on cost in favour of
  Strimzi. NAT Gateway avoided by placing nodes in public subnets — a
  demo-only deviation, documented as such.
- **Scope note:** `conveyor-verifier` is a sixth deployable but is a *test
  instrument*, never in a request path, with a read-only database role. Its
  ability to read across service boundaries is the point (Anvil's inside-out
  checking), and is explicit and revocable.
- **ADR-13 (2026-09-10) — zero-cost deployment, at the human's explicit
  instruction ("would not want to spend a single penny... stick to free tier
  or cut the scope if required").** The terminal deployment target changed
  from AWS EKS/RDS/ECR/S3+CloudFront to kind/k3d + GHCR + Vercel/Cloudflare
  Pages + containerized Postgres, with Atlas M0 kept (already free forever).
  Terraform for the original AWS design is still written and `plan`-validated
  in CI, but `apply` never runs — the IaC skill is demonstrated without spend.
  **Named, honest cost of this decision:** node-level cluster autoscaling
  (Karpenter/Cluster Autoscaler adding real cloud nodes) cannot be measured
  without a real cloud account and is recorded as an explicit, tested-absent
  limitation in `docs/LIMITATIONS.md` (Phase 16), not hidden. Everything else
  in the plan — the Saga/2PC thesis, all five services, the dashboard, and the
  entire rigor stage (invariant checker, chaos matrix, load test) — was never
  AWS-dependent and is unaffected. Full reasoning in `ARCHITECTURE.md` ADR-13;
  the original costed AWS path is retained as a documented, not-executed
  fallback in `ARCHITECTURE.md` §15.4 and `PLAN.md` Phase 14.
- **Phase 7 — dispatch's two stores use two different idempotency mechanisms,
  not one shared inbox.** `recordShipment` (Postgres) is inbox-guarded exactly
  like every other consumer. `writeNotification` (Mongo) instead upserts a
  document whose `_id` is a deterministic `orderId:ORDER_CONFIRMED` string —
  Mongo has no shared transaction with the Postgres inbox table, so rather
  than trying to fake atomicity across two databases, the write itself is made
  idempotent (same input → same final document, redelivery or not). This is
  what PLAN.md's "the Mongo write retried independently" means in practice.
- **Phase 7 — the Kafka retry-then-DLQ error handler
  (`dispatch-service/config/KafkaErrorHandlingConfig`) is scoped to
  dispatch-service alone, not added to conveyor-common.** Dispatch is the one
  consumer in this project whose failure has no saga-level compensation to
  fall back on — inventory/payment/saga-orchestrator's own failure handling is
  the saga timeout/compensation machinery, and giving them an independent
  bounded-retry-then-give-up path on top would be a second, conflicting
  failure-handling mechanism. `<topic>.dlq` (1 partition) is declared as a
  `NewTopic` bean rather than relying on auto-creation, so the recoverer can
  always target partition 0 regardless of the source topic's partition count.
- **Phase 7 — the `e2e` module's three required scenarios are split across two
  `docker compose` stacks, and the third compensation path ("payment succeeds,
  then an operator aborts anyway") is deliberately *not* re-proven there.**
  Happy path and insufficient-stock compensation run organically against the
  default stack; payment-decline compensation needs
  `CONVEYOR_PAYMENT_GATEWAY_DECLINE_RATE=1` for *every* charge, so it runs
  against a second stack (a new, default-`0`, harmless passthrough added to
  `docker-compose.yml`'s payment-service block for exactly this). The operator-
  abort path requires racing the saga's own pivot commit (abort must land
  between `PaymentCharged` and the pivot's `OrderConfirmed` publish) — no
  chaos/delay hook exists at that exact point, and racing it over a live
  compose network path would make the suite flaky by construction. That path
  is already covered deterministically at the service level by
  `saga-orchestrator`'s `SagaCompensationIntegrationTest` from Phase 6; the e2e
  suite's job is proving the pipeline is wired end to end, not re-deriving an
  invariant a faster, non-flaky test already establishes.
- **Phase 7 — the `e2e` module is excluded from the default `./mvnw verify`**
  (`skipTests` defaults true via its own `skipE2E` property) and instead runs
  in a dedicated `e2e` job in `build.yml`, the same reasoning Phase 1's
  `kafka-compat` job split applies: it needs `docker compose build` for all
  five images plus real wall-clock time no other module's Testcontainers suite
  needs, so it would slow down every ordinary `verify` run for a check CI can
  run in parallel instead. **It also isn't a root-reactor module at all**
  (BUGS.md BUG-0013): declaring it in the root pom's `<modules>` broke every
  service's own Dockerfile build, since Maven resolves the full module list
  before `-pl` filtering and none of the Dockerfiles `COPY` `e2e/pom.xml`.
  Run standalone via `mvn -f e2e/pom.xml verify -DskipE2E=false` instead.

## Discrepancies against `PROJECT_BRIEF.md`
Per `CLAUDE.md` §0, logged rather than silently edited into the brief.

- **Brief §3 says "four independently deployable services."** ADR-1 makes it
  five (`saga-orchestrator`), plus `conveyor-verifier` as a non-product test
  instrument. The brief anticipated this by listing "a dedicated orchestrator
  service" as an option under OPEN-1, so this is a resolution of an open item
  rather than a contradiction — but it is recorded here because it changes the
  system's shape.
- **Brief §5 describes Inventory consuming `OrderPlaced` and Payment consuming
  `InventoryReserved` directly.** That is the choreography phrasing. Under
  ADR-1 they consume *commands* from the orchestrator (`ReserveInventory`,
  `ChargePayment`) and publish the same reply events the brief names. The event
  vocabulary is preserved; the routing is not.

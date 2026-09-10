# Context — Last updated: 2026-09-10 (Phase 7 session)

See `CLAUDE.md` §6 for the format policy: this file always reflects *current*
state, overwritten in place, not appended forever. Keep it readable in under
a minute.

## Current Phase
**Phase 7 — Dispatch/Notification Service and full-pipeline E2E · `./mvnw
verify` green (135/135, full 8-module reactor) — blocked only on the `e2e`
module's own live `docker compose` run.** `dispatch-service` now has a real
`OrderConfirmedListener` → `DispatchService` consuming the saga's pivot event
(inbox-guarded `shipments` row in Postgres; an idempotent-by-construction
Mongo upsert for the notification log — see Key Decisions Log for why two
different idempotency mechanisms), publishing `ShipmentCreated` via the
outbox, and a Kafka retry-then-DLQ error handler scoped to this service alone.
`GET /shipments/{orderId}`, `GET /notifications?orderId=`. **4/4 new
dispatch-service test classes green**, including the retry-and-DLQ test (a
poison message is retried per its `FixedBackOff`, republished to
`conveyor.order.events.v1.dlq`, `conveyor_dlq_messages_total` increments, and
the next order on the same partition is unaffected) — independently
reconfirming this session, not trusted from compilation alone.

A new `e2e` module (Testcontainers' Docker Compose, driving only the public
REST API) covers the happy path plus two of the three required compensation
paths for real, over two live compose stacks; a third stack scenario is
deliberately not attempted (see Key Decisions Log). One real bug was found and
fixed building it: adding `e2e` to the root reactor broke every service's
Dockerfile build (BUGS.md BUG-0013). After that fix, the module's own live run
got correctly past dependency resolution into the actual image builds before
failing with what first looked like Docker Desktop/WSL2 disk corruption —
`df -h` immediately after showed the actual cause: **the host `C:` drive is
full again (226 GB / 226 GB used, 0 bytes free — BUG-0007 recurring)**, not
new corruption. Not fixable from here; needs the human to free space on `C:`
or move Docker Desktop's data root to `D:` (160 GB free). Phase 6 (Saga
Orchestrator) remains exactly where the last session left it: code complete,
its own live-compose exit criterion still open on the same disk-space issue.

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
- Phase 6 — Saga Orchestrator: **code complete, `./mvnw verify` green, live
  `docker compose` smoke test blocked** (2026-09-10). `saga-orchestrator`
  now has a real `SagaOrchestrationService` driving `OrderPlacedListener` →
  `ReserveInventory` → (reply) → `ChargePayment` → (reply) → the pivot
  (`tryConfirm`, its own transaction — see that class's Javadoc for why) →
  `OrderConfirmed`; every compensation path (`InventoryReservationFailed`,
  `PaymentFailed`, and an operator-initiated `POST /sagas/{id}/abort` for the
  "payment succeeded, then abort anyway" path — see Key Decisions Log for why
  that's the legitimate trigger, not a blind timeout-driven refund);
  `SagaTimeoutSweeper` (`FOR UPDATE SKIP LOCKED`, same pattern as
  `OutboxPoller`) claims expired non-terminal sagas every `sweep-interval` and
  applies §7.4's timeout policy, escalating to `NEEDS_INTERVENTION` after
  `max-compensation-attempts`; `POST /sagas/{id}/retry` re-drives it.
  `GET /sagas/{orderId}`, `GET /sagas?state=&stuck=true`. All 5 saga metrics
  from §11 wired (`conveyor_saga_active` as a live gauge over
  `saga_instances`, not a counter). Two chaos points wired
  (`saga.after-reply-before-state-write`, `saga.after-state-write-before-command`).
  Closed a real spec gap along the way — `OrderPlaced` never carried
  `paymentMethodToken` (BUG-0011) — by threading it through `Order`,
  `OrderPlacedPayload`, and its schema, plus a small
  `Order`/`OrderPlacedPayload` migration. **21/21 new tests green**
  (`SagaHappyPathIntegrationTest`, `SagaCompensationIntegrationTest` ×3,
  `SagaTimeoutIntegrationTest` ×2, `SagaIdempotencyIntegrationTest`,
  `SagaConcurrentSweepIntegrationTest`, `SagaCommandAndEventContractTest` ×2,
  `SagaControllerIntegrationTest` ×3, plus the 4 pre-existing scaffold tests),
  **131/131 across the full reactor** (`./mvnw verify`). Only the live
  `docker compose up` → "a manually placed order reaches `CONFIRMED`" exit
  criterion remains unverified — see Blockers.

## In Progress
- **Nothing mid-flight.** `./mvnw verify` is green (135/135, full reactor),
  independently reconfirmed live this session. The only remaining action for
  Phase 7 is the `e2e` module's own live run, blocked on host disk space (see
  Blockers) rather than anything left to build or fix in the code.

## Blockers
- **The host `C:` drive is completely full (226 GB / 226 GB used, 0 bytes
  free) — BUG-0007 recurring**, discovered this session via `df -h` right
  after the `e2e` module's live run failed with what initially looked like
  fresh Docker Desktop/WSL2 disk corruption (`input/output error` writing
  containerd's own metadata database). The full disk is almost certainly the
  actual cause of that symptom, not new corruption — see BUGS.md BUG-0007 and
  BUG-0008's latest updates for the full reasoning. This blocks only the
  `e2e` module's live multi-container run and Phase 6's still-open live
  `docker compose` smoke test; `./mvnw verify` itself (Testcontainers-only,
  much smaller footprint) ran clean at 135/135 earlier in this same session.
  Not something to hunt through and free up unilaterally on a system drive
  outside this repo (`CLAUDE.md`'s guidance on host-wide risky actions) —
  this project's own Docker footprint (images/volumes) is nowhere near large
  enough to explain 226 GB.
  **Unblocks when:** the human frees space on `C:`, or points Docker
  Desktop's data root at `D:` (160 GB free). Then:
  `mvn -f e2e/pom.xml verify -DskipE2E=false` closes Phase 7's full-pipeline
  exit criterion, and re-running `docker compose up -d --build` closes
  Phase 6's still-open one too.

## Next Steps
1. Once `C:` has room: run `mvn -f e2e/pom.xml verify -DskipE2E=false` and
   confirm both E2E test classes (happy path + insufficient-stock compensation
   on one compose stack, payment-decline compensation on a second) pass
   against the real containers.
2. Only then: check off Phase 7's remaining exit criteria in `PLAN.md`, mark
   it complete in this file, and move to Phase 8 (Live ops dashboard).
3. Separately, revisit Phase 6's still-open live `docker compose` smoke test
   — likely satisfied for free once (1) brings the same stack up healthy.

## Toolchain note (this machine)
- Java **25 LTS** installed (not 21). No discrepancy with ADR-4: POMs compile
  with `maven.compiler.release=21`, and JDK 25 runs release-21 bytecode
  natively. Spring Boot 3.5.x version pinned in the parent POM once written.
- Maven is **not** installed globally → the project uses the **Maven
  Wrapper** (`mvnw`/`mvnw.cmd`), committed to the repo, so no local Maven
  install is required by anyone building this.
- Docker 28.3 + Compose v2.38 confirmed working as of Phase 2; unresponsive as
  of this session (BUG-0008) — status, not a version change.

## Key Decisions Log

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

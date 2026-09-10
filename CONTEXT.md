# Context — Last updated: 2026-09-10 (Phase 6 session)

See `CLAUDE.md` §6 for the format policy: this file always reflects *current*
state, overwritten in place, not appended forever. Keep it readable in under
a minute.

## Current Phase
**Phase 6 — Saga Orchestrator · Code complete, `./mvnw verify` green — blocked
only on the live `docker compose` smoke test.** Every exit criterion has a
passing test (21/21 new saga-orchestrator tests, 131/131 across the full
7-module reactor). Phase 3 (Order Service) closed out for real this session —
`docker compose up -d --build` was independently verified with all 8
containers `Healthy` and all 5 `/actuator/health` endpoints `UP` — before a
later re-attempt (for Phase 6's own compose exit criterion) hit the same
Docker Desktop instability again; see Blockers.

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
- **Nothing mid-flight.** Phase 6's code and tests are done; the only
  remaining action for either phase is the live compose smoke test, which is
  blocked on the host's Docker Desktop instability, not on anything left to
  build.

## Blockers
- **Phase 6's live `docker compose` smoke test is blocked on the same
  recurring Docker Desktop/WSL2 instability as before** (BUGS.md BUG-0008,
  updated again this session with a fourth-plus distinct symptom). This
  session actually *did* get a full clean run earlier — `docker compose down
  -v` + `up -d --build` brought all 8 containers `Healthy` with all 5 health
  endpoints `UP`, closing Phase 3 for good — but a later re-attempt (to
  smoke-test Phase 6's saga flow through the real stack) hit BuildKit dying
  mid-build again (`ClassFormatError` in two of the five build stages, then
  `rpc error: code = Unavailable ... EOF`), and `docker ps`/`docker compose
  ps` are back to hanging. Not something to restart/reset unilaterally per
  `CLAUDE.md`'s guidance on host-wide risky actions.
  **Unblocks when:** the human gets Docker Desktop through one clean
  `docker compose up -d --build`; then `POST /api/v1/orders` through to
  `GET /api/v1/sagas/{orderId}` showing `state: COMPLETED` closes Phase 6 for
  good.

## Next Steps
1. Once Docker survives a full build: `docker compose up -d --build`, place
   an order via `POST /api/v1/orders`, and confirm it reaches
   `orders.status = CONFIRMED` / `GET /sagas/{orderId}` → `COMPLETED`. This is
   the only thing left to mark Phase 6 complete.
2. Begin Phase 7 — Dispatch/Notification Service and full-pipeline E2E
   (`PLAN.md`). Its dependency (Phase 6's code, not the live-compose check) is
   satisfied, so this can start without waiting on item 1.

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

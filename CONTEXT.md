# Context — Last updated: 2026-09-10

See `CLAUDE.md` §6 for the format policy: this file always reflects *current*
state, overwritten in place, not appended forever. Keep it readable in under
a minute.

## Current Phase
**Phase 2 — Data layer, domain model and migrations · In progress**

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
  all 5 `/actuator/health` endpoints returning `UP` — verified this session,
  stack torn down afterward.

## In Progress
- Starting Phase 2 per `PLAN.md`: Flyway migrations per service (including
  the oversell `CHECK` constraint and idempotency-key unique constraints),
  `outbox`/`inbox` tables in all five service databases, JPA entities +
  Spring Data repositories, MongoDB `$jsonSchema` validators on `catalog` and
  `notifications`, seed data (~50 catalog SKUs, `ops`/`admin` users), and
  per-service DB roles/grants including the verifier's read-only role.

## Blockers
- None.

## Next Steps
1. Design the per-service schemas (tables, indexes, constraints) matching
   `ARCHITECTURE.md` §5.
2. Write Flyway `V1__baseline.sql` migrations per service.
3. Add JPA entities/repositories and Mongo validators; wire seed data and
   `make seed`.
4. Write the Phase 2 exit-criterion tests (Flyway-to-head, repository CRUD,
   oversell constraint, Mongo validator rejection, verifier role privilege
   test) and get them green before marking Phase 2 complete.

## Toolchain note (this machine)
- Java **25 LTS** installed (not 21). No discrepancy with ADR-4: POMs compile
  with `maven.compiler.release=21`, and JDK 25 runs release-21 bytecode
  natively. Spring Boot 3.5.x version pinned in the parent POM once written.
- Maven is **not** installed globally → the project uses the **Maven
  Wrapper** (`mvnw`/`mvnw.cmd`), committed to the repo, so no local Maven
  install is required by anyone building this.
- Docker 28.3 + Compose v2.38 confirmed working.

## Key Decisions Log

Full reasoning for each is in `ARCHITECTURE.md` §3.

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

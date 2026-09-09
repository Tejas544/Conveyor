# PROJECT_BRIEF.md — Conveyor

> **Status:** Draft, pending Phase 0 sign-off. Once the human approves Phase 0,
> this document is **frozen**. Per `CLAUDE.md` §0, discrepancies discovered
> during the build are logged in `CONTEXT.md` → "Key Decisions Log" and worked
> around — they are not silently edited into this file.
>
> **Source:** the project kickoff message, 2026-09-09. This document is a
> faithful capture of that message, reorganised for reference. Where the
> kickoff left something open, it is marked **[OPEN]** and resolved in
> `ARCHITECTURE.md` §3 — not here.

---

## 1. Why this project exists

Conveyor is the third project in a deliberate portfolio sequence:

| Project | What it proves | Layer |
|---|---|---|
| **Anvil** | 2PC / SSI, deterministic simulation testing, protocol invariants | Database engine |
| **EdgeRAG** | On-device multimodal RAG under a memory budget; live SSE streaming UX | ML systems / serving |
| **Conveyor** | **Saga-pattern distributed transactions at the service layer**; cloud-native microservices; live ops dashboard | Distributed application |

It also mirrors the domain of the author's Amazon internship — **order dispatch
and inventory allocation** — so it reads as a natural extension of that
experience, not a pivot.

**The throughline, which governs how everything is named, documented and
demoed:**

> Anvil solved the distributed-transaction problem at the storage-engine layer
> with 2PC. Conveyor solves the *same problem at the service layer* with Saga —
> where there is no prepare phase, no blocking coordinator, and rollback is
> semantic rather than physical. The ops dashboard reuses the SSE/streaming
> instinct from EdgeRAG.

**The interview line this must earn:**

> "Anvil taught me 2PC at the storage-engine level; Conveyor is where I applied
> Saga at the service level — same problem, different layer."

The point is *not* "another CRUD app with Kafka." Every component must be
traceable to a claim it substantiates.

**Working name:** Conveyor. Changeable if something better emerges, but must
then be used consistently everywhere.

---

## 2. What Conveyor is

A cloud-native, event-driven **order fulfillment platform**.

A customer places an order. The system reserves inventory, charges payment, and
confirms the order — or, if any step fails, correctly and **observably**
compensates (releases inventory, refunds payment) so the system never ends up
in an inconsistent state. The whole pipeline is visible live on an operations
dashboard.

---

## 3. Backend — Java microservices (Spring Boot)

Four independently deployable services were specified in the kickoff:

### 3.1 Order Service
- Owns the order aggregate and its state machine:
  `PLACED → INVENTORY_RESERVED → PAYMENT_CHARGED → CONFIRMED`,
  with `CANCELLED` / `COMPENSATING` branches on failure.
- Exposes the REST API that customers and the dashboard talk to.
- Publishes `OrderPlaced`.

### 3.2 Inventory Service
- Owns stock levels and reservations.
- Consumes `OrderPlaced`, attempts to reserve stock, publishes
  `InventoryReserved` or a failure event.
- Must handle the compensating "release reservation" event if a later step
  fails.

### 3.3 Payment Service
- Simulates charging a payment method. **A mocked gateway is fine** — this is
  explicitly not the place to integrate a real payment processor.
- Consumes `InventoryReserved`, publishes `PaymentCharged` or a failure event.
- Must support **idempotent** charge attempts and **refund** (compensating) on
  downstream failure.

### 3.4 Dispatch / Notification Service
- Consumes `Confirmed`, creates a shipment record, writes notification-log
  entries. Email/SMS may be mocked/logged rather than actually sent.

> **Weight note from the kickoff, quoted because it sets the bar:** "This is the
> biggest unproven claim in my story (Java at this depth, microservices, system
> design, REST) — it should carry real weight, not be a thin wrapper."

**[OPEN-A]** Whether an additional saga-coordination component exists is a
consequence of the Saga-style decision below. See `ARCHITECTURE.md` §3.1.

---

## 4. Data layer — deliberately mixed, not forced

- **PostgreSQL** for transactional state that needs ACID: orders, the inventory
  stock ledger / reservations, payments.
- **MongoDB** for data that is naturally document-shaped: product catalog
  metadata, notification logs.
- The split must be **architecturally justified** in `ARCHITECTURE.md`, not
  arbitrary. The point is demonstrating *when* to reach for which — not that
  both have been touched.

---

## 5. Messaging — Kafka

Event bus carrying the order lifecycle:

```
OrderPlaced → InventoryReserved → PaymentCharged → Confirmed
```

with Saga compensating transactions on failure — e.g.
`InventoryReservationFailed`, `PaymentFailed`, `InventoryReleaseRequested`.

**[OPEN-1] Saga style — choreography vs. orchestration** is a real design
decision and must not be defaulted into silently. If orchestration: is it a
dedicated orchestrator service, or logic embedded in Order Service?

Consumers must be idempotent (`CLAUDE.md` §7).

---

## 6. Frontend — React + TypeScript

### 6.1 Live ops dashboard
- Order pipeline updating **in real time** via SSE or WebSocket — a direct
  callback to the SSE work in EdgeRAG.
- Kanban-style view by order state.
- Per-order timeline showing each saga step as it happens.
- **This is the most demo-able part of the project; it should look good, not
  just work.**

### 6.2 Admin panel for inventory
- View/adjust stock.
- Surface low-stock conditions.

---

## 7. Infrastructure & cloud

- **Docker** per service, multi-stage builds.
- **Kubernetes (EKS)** for orchestration and autoscaling.
- **AWS:** RDS (Postgres), EKS, MSK or self-hosted Kafka, S3/CloudFront for the
  frontend.
- **[OPEN-3] MongoDB hosting in production:** Atlas vs. DocumentDB vs.
  self-hosted on EKS.
- **Local development must not require AWS at all.** The full stack —
  Postgres, Mongo, Kafka (or a lighter Kafka-API-compatible broker), all
  services, the frontend — runs via `docker-compose`. AWS is the deployment
  target, not the dev loop.
- **GitHub Actions CI/CD:** build → test → containerize → push to ECR → deploy.

---

## 8. Rigor plan

> Quoted framing from the kickoff: "this is what makes it match Anvil's bar, not
> optional polish."

1. **Chaos-test the saga.** Kill a service mid-transaction, repeated enough
   times to produce a real percentage rather than an anecdote (a few dozen
   trials is a reasonable starting point). Measure the **compensation
   correctness rate**. Target 100%; if it is not 100%, that is a real finding —
   write it up rather than hiding it.
2. **Load-test the full pipeline** (k6 or Gatling): throughput and p99 latency
   under concurrent orders. Measure honestly; do not chase a target number for
   its own sake. Record in `RESULTS.md`.
3. **Invariant checker.** No order should ever reach `CONFIRMED` with
   insufficient inventory actually reserved for it — the same spirit as Anvil's
   Elle checker. **Runs continuously or on a schedule, not as a one-off
   script.**
4. **Autoscaling measurement.** Drive load against the cluster; record pod count
   vs. load and scale-up latency for at least one horizontally autoscaled
   service.

---

## 9. Non-functional & process expectations

- **Observability:** structured logging, metrics (e.g. Micrometer +
  Prometheus), and ideally distributed tracing (e.g. OpenTelemetry) across
  services. Without this, the chaos/load results are hard to interpret.
- **Security basics:** no hardcoded secrets; env-var-based config; some form of
  auth on the admin panel. **[OPEN-5]** JWT is a reasonable default but the
  approach is an open decision.
- **Documentation:** root `README.md` with setup instructions and an
  architecture diagram; `ARCHITECTURE.md` with real design detail; short
  **ADR-style notes** for any decision that could reasonably have gone another
  way.
- **Cost awareness:** AWS costs real money. Before creating any resource that
  is not free-tier eligible, say so. Keep a documented teardown path (script or
  exact CLI steps) so nothing keeps costing money after a demo session.

---

## 10. Open decisions requiring an explicit recommendation

These must not be silently picked. Each needs a short recommendation with
reasoning in `ARCHITECTURE.md`, flagged explicitly at Phase 0 sign-off.

| # | Decision |
|---|---|
| **OPEN-1** | Saga style — choreography vs. orchestration (and if orchestration, what drives it: a dedicated orchestrator service, or logic embedded in Order Service?) |
| **OPEN-2** | Kafka for local dev — real Kafka in Docker vs. a lighter Kafka-API-compatible broker (e.g. Redpanda) |
| **OPEN-3** | MongoDB hosting in production — Atlas vs. AWS DocumentDB vs. self-hosted on EKS |
| **OPEN-4** | Java build tool — Maven vs. Gradle |
| **OPEN-5** | Auth approach for the admin panel |
| **OPEN-6** | Event schema format — plain versioned JSON vs. Avro + Schema Registry (the latter a legitimate stretch goal, not a requirement) |

For anything **not** on this list: use judgment, document the assumption, keep
moving. The human explicitly does not want to be a bottleneck on small
decisions.

---

## 11. Process

Governed by `CLAUDE.md`, which is binding. Phase 0 deliverables:

1. Read `CLAUDE.md`, `BUGS.md`, `CONTEXT.md`. ✅
2. Write `PROJECT_BRIEF.md` (this file). ✅
3. Write `ARCHITECTURE.md`.
4. Write `PLAN.md` — phase count, boundaries and order are the agent's call;
   the rigor plan must appear as real scheduled phases, not an afterthought.
5. Update `CONTEXT.md`.
6. Present a summary and **stop**. No implementation code until an explicit go.

From Phase 1 onward: test each phase before moving on, log bugs the moment they
are found, update `CONTEXT.md` continuously, checkpoint at phase boundaries and
on any decision with more than one reasonable answer, and never report anything
done until it actually is.

---

## 12. Definition of Done (from `CLAUDE.md` §8, restated here for one-file reference)

- All services independently deployable; running together via `docker-compose`
  locally and via K8s manifests/Helm on EKS.
- Full happy-path order flow works end to end and is visible live on the
  dashboard.
- ≥1 chaos scenario demonstrated (service killed mid-saga) with a measured
  compensation-correctness rate in `RESULTS.md`.
- Load-test report: throughput and p99 latency under concurrent orders, in
  `RESULTS.md`.
- Inventory invariant checker running, zero violations — or documented
  violations with root cause.
- Autoscaling behaviour measured and recorded (pod count vs. load, scale-up
  latency).
- CI/CD pipeline green end to end: build → test → containerize → push to ECR →
  deploy.
- Root `README.md`: what this is, an architecture diagram, how to run it
  locally, how it is deployed, and what each part of the stack demonstrates.

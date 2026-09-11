# INVARIANTS.md — Conveyor's invariant catalogue

Conveyor's analogue of Anvil's `docs/INVARIANTS.md`. Graduated out of `ARCHITECTURE.md` §13 in
Phase 10, once `conveyor-verifier` actually implemented, tested, and measured every row below —
this file describes what was *built*, not what was planned.

`conveyor-verifier` evaluates every invariant in two independent modes:

- **Inside-out**: direct, read-only SQL against each service's own Postgres database, via the
  `conveyor_verifier` role (`SELECT` only, granted per-database — ARCHITECTURE.md §12). This is
  the **deployed authority** — the only mode that drives `conveyor_invariant_violations_total`,
  the Prometheus alert, and `make invariant-check`'s exit code.
- **Outside-in**: the exact same public REST endpoints the dashboard and any other API consumer
  already use — nothing added for the checker's own benefit. Run once per `make invariant-check`
  invocation as a diagnostic comparison, never on the continuous 10s loop.

The comparison is not a strawman: the outside-in mode is free to call **all five services**, not
just one, and does. Even so, three invariants are structurally invisible to it — see the table.

## The catalogue

| ID | Class | Statement | Inside-out | Outside-in |
|---|---|---|---|---|
| **INV-INV-01** | safety | `on_hand - reserved >= 0` for every SKU. No oversell. | ✅ `stock_items` | ✅ `GET /inventory` |
| **INV-INV-02** | safety | `stock_items.reserved == Σ reservations.quantity WHERE status = HELD`, per SKU. | ✅ join | ✅ `GET /inventory` + `GET /inventory/{sku}/reservations` |
| **INV-INV-03** | safety | Conservation: `on_hand == Σ stock_adjustments.delta − Σ COMMITTED reservations.quantity`, per SKU. | ✅ | ❌ *not observable* |
| **INV-ORD-01** | safety | **The headline.** Every `CONFIRMED` order has, per line item, a `COMMITTED` reservation of the matching quantity. | ✅ two databases, correlated in Java | ✅ `GET /orders` + `GET /inventory/{sku}/reservations` |
| **INV-ORD-02** | safety | No `CANCELLED` order has a `HELD` reservation. | ✅ | ✅ `GET /orders` + `GET /inventory/{sku}/reservations` (see note) |
| **INV-ORD-03** | safety | No `CANCELLED` order has a payment `CAPTURED` without a matching `REFUNDED`. | ✅ | ✅ `GET /orders` + `GET /payments/{orderId}` |
| **INV-ORD-04** | safety | Every `CONFIRMED` order has exactly one payment `CAPTURED` for exactly `total_amount`. | ✅ | ✅ `GET /orders` + `GET /payments/{orderId}` |
| **INV-PAY-01** | safety | At most one `CAPTURED` payment per order. | ✅ *(see below)* | ❌ *not observable* |
| **INV-SAGA-01** | safety | Every non-terminal saga has a non-null `deadline_at`. | ✅ | ✅ `GET /sagas` |
| **INV-SAGA-02** | safety | Every terminal saga's `FORWARD SUCCEEDED` step is either in a `COMPLETED` saga or has a matching `COMPENSATION SUCCEEDED` step. | ✅ | ✅ `GET /sagas` + `GET /sagas/{orderId}` |
| **INV-SAGA-03** | liveness | No saga non-terminal for longer than `maxSagaAge` (5 min). | ✅ | ✅ `GET /sagas` + `GET /sagas/{orderId}` (createdAt) |
| **INV-SAGA-04** | liveness | `count(NEEDS_INTERVENTION) == 0`. | ✅ | ✅ `GET /sagas?state=NEEDS_INTERVENTION` |
| **INV-SAGA-05** | convergence | For every saga terminal > 30s, `orders.status` agrees with the saga outcome. | ✅ | ✅ `GET /sagas` + `GET /sagas/{orderId}` + `GET /orders/{id}` |
| **INV-DSP-01** | safety | Every `CONFIRMED` order has exactly one shipment; no `CANCELLED` order has one. | ✅ | ✅ `GET /orders` + `GET /shipments/{orderId}` |
| **INV-BOX-01** | liveness | No `outbox` row unpublished for more than 60s, in any of the five databases. | ✅ | ❌ *not observable* |

**12 of 15 are observable outside-in** once the checker is allowed to aggregate across all five
services' already-public endpoints — more than ARCHITECTURE.md §13's single-endpoint illustration
implied. The measured counts (from the seeded-violation harness) are in `RESULTS.md`.

## Why the three gaps are structural, not an implementation shortfall

- **INV-INV-03** (conservation): `stock_adjustments` — the audit trail every unit of stock is
  traceable to (see BUG-0021's seed-time fix) — has no `GET` endpoint anywhere in the public API,
  and shouldn't: a client has no legitimate reason to see the full adjustment ledger, only its
  effect (`GET /inventory` already exposes `on_hand`/`reserved`). This is ARCHITECTURE.md §13's
  own canonical example, made concrete rather than asserted.
- **INV-PAY-01** (at most one `CAPTURED` payment per order): `payments.order_id unique` makes a
  second row for one order structurally impossible via any application write — and `GET
  /payments/{orderId}` (`findByOrderId`, an `Optional`-returning query) *assumes* that invariant
  already holds. If the constraint were ever bypassed and a second row existed, the endpoint would
  silently return one of them, never both — the API's own contract can't be used to check the
  property it depends on. The same reasoning makes `INV-DSP-01`'s "exactly one shipment" case
  outside-in-*complete* rather than partial, since `shipments.order_id` carries the identical
  unique constraint but the endpoint still correctly reports "shipment exists / doesn't," which is
  all that invariant needs.
- **INV-BOX-01** (outbox lag): `outbox` is pure internal infrastructure (ADR-7) — no service
  exposes it, and none should. This is the other half of ARCHITECTURE.md §13's illustration.

## Two invariants a real Postgres constraint makes impossible to violate normally

`INV-INV-01`'s table `CHECK (on_hand - reserved >= 0)` and `INV-PAY-01`'s `payments.order_id
unique` both make their own violation structurally impossible via any application-level write —
the database itself is the enforcement mechanism, which is the point (ADR-9). Per ARCHITECTURE.md
§13's rule that an invariant that can't be violated by construction must be **named as such, not
counted as a pass**: both ARE constructible for the seeded-violation harness, just not through any
code path a service actually exercises — `InvariantSoundnessTest` drops the constraint first
(`SeedHelpers.dropConstraints`), corrupts the state, and restores nothing afterward (the shared
per-class Testcontainers Postgres instance is torn down at the end of the test class regardless),
recorded inline rather than silently working around it.

## A correction to ARCHITECTURE.md §13's own illustration

§13 uses "a reservation held for a cancelled order is invisible to `GET /orders/{id}`" as its
motivating example for inside-out checking (INV-ORD-02). That claim is true for a checker that
only ever calls one endpoint — but `conveyor-verifier`'s outside-in mode also calls
inventory-service's `GET /inventory/{sku}/reservations`, and **does** see it
(`OutsideInCoverageTest#inv0rd02DetectsAHeldReservationOnACancelledOrderByAggregatingTwoServices`).
The real, structural gaps are the three above, not this one — worth stating precisely rather than
letting a simplified illustration stand as the final word once the checker was actually built.

## Negative controls and precision

- **Soundness**: `InvariantSoundnessTest` (conveyor-verifier) — for all 15 invariants, a
  deliberately corrupted database state is constructed and the inside-out checker flags it with the
  correct offending ID. 16/16 test methods green (INV-DSP-01 has two: the missing-shipment and the
  stray-shipment directions).
- **Precision**: `InvariantPrecisionTest` — 500 independent, clean order/saga lifecycles (300
  confirmed happy-path, 150 cancelled-and-compensated, 50 in-flight) seeded into one shared
  snapshot; the full catalogue reports zero violations across all of them. See CONTEXT.md's Key
  Decisions Log for why these are synthetic-but-schema-faithful states rather than 500 literal
  sagas driven through Kafka.
- **Outside-in coverage**: `OutsideInCoverageTest` — a representative sample (same-service,
  cross-service, and the three not-observable invariants) verified against `MockRestServiceServer`
  stubs built from the real controller/DTO shapes, not guessed ones.

## Deployment

- **Local ("sidecar")**: `docker-compose.yml`'s `conveyor-verifier` service, always on (not gated
  behind `--profile observability`) — `ScheduledVerificationRunner` re-evaluates the inside-out
  catalogue every 10s (`conveyor.verifier.interval`), publishes
  `conveyor_invariant_violations_total{invariant}`, and writes `latest-report.json` /
  `violations.jsonl` to the `verifier-reports` volume.
- **One-shot**: `make invariant-check` (`SPRING_PROFILES_ACTIVE=oneshot`) — runs both modes once,
  logs the inside-out/outside-in coverage comparison, and exits non-zero iff any inside-out
  violation was found. This is what CI gates on.
- **K8s (written, not yet applied — no cluster exists before Phase 13)**:
  `infra/k8s/conveyor-verifier-cronjob.yaml`, the one-shot mode on a `CronJob` schedule.

# RESULTS.md — Conveyor's rigor-phase measurements

Per `CLAUDE.md` §1/§2.12: chaos/load/autoscaling results, recorded as each rigor test is run.
This file grows one section per phase (10, 11, 12, 15) — never rewritten, only appended to.

---

## Phase 10 — `conveyor-verifier`: the invariant checker (2026-09-11)

### Methodology

Two independent checking modes evaluate the same 15-invariant catalogue (`docs/INVARIANTS.md`):

- **Inside-out**: direct SQL against each service's own Postgres database via the read-only
  `conveyor_verifier` role.
- **Outside-in**: the same public REST endpoints any other API client uses, aggregated across all
  five services (not a single-endpoint strawman).

Soundness (does the checker detect a real violation?) is established by
`InvariantSoundnessTest`: for each invariant, a database state that violates *exactly* that
invariant is constructed directly via JDBC (bypassing the application layer entirely — for two
invariants protected by a real Postgres constraint, the constraint itself is dropped first), and
the inside-out checker is asserted to flag it with the correct offending ID. Precision (does the
checker stay quiet on legitimate states?) is established by `InvariantPrecisionTest`: 500
independent, clean order/saga lifecycles are seeded into one shared snapshot and the full catalogue
is asserted to report zero violations.

Reproduce: `./mvnw -pl conveyor-verifier -am test -Dtest=InvariantSoundnessTest,InvariantPrecisionTest,OutsideInCoverageTest`.

### Soundness — negative controls (16/16 green)

Every one of the 15 invariants has at least one seeded-violation test (INV-DSP-01 has two: missing
shipment on a confirmed order, and a stray shipment on a cancelled one). All 16 flagged the correct
offending ID on the first real run against Testcontainers Postgres — no test needed a second attempt
to get its seeding logic right, which is itself worth recording: it means the SQL each invariant
runs was correct against the actual schema on the first try, not fixed up after a false pass.

Two invariants (`INV-INV-01`, `INV-PAY-01`) are enforced by a real Postgres constraint
(`CHECK (on_hand - reserved >= 0)`, `payments.order_id UNIQUE`) and are therefore **structurally
impossible to violate via any application-level write** — their seeded-violation tests drop the
constraint first. Per ARCHITECTURE.md §13's rule, this is named explicitly here rather than counted
as an unqualified pass: both are constructible, just not through any code path a service itself
exercises.

### Precision (1/1 green)

500 clean states (300 confirmed happy-path, 150 cancelled-and-compensated, 50 in-flight
non-terminal sagas), one shared database snapshot, one catalogue run: **zero violations** across
all 15 invariants. See `CONTEXT.md`'s Key Decisions Log for why these are synthetic-but-schema-
faithful states rather than 500 sagas literally driven through Kafka end to end.

### Inside-out vs. outside-in — measured coverage

| Invariant | Inside-out | Outside-in | Gap reason |
|---|---|---|---|
| INV-INV-01 | ✅ detects | ✅ detects | — |
| INV-INV-02 | ✅ detects | ✅ detects | — |
| INV-INV-03 | ✅ detects | ❌ not observable | `stock_adjustments` has no GET endpoint |
| INV-ORD-01 | ✅ detects | ✅ detects | — (cross-service: order-service + inventory-service) |
| INV-ORD-02 | ✅ detects | ✅ detects | — (cross-service; see INVARIANTS.md's correction to §13's own illustration) |
| INV-ORD-03 | ✅ detects | ✅ detects | — |
| INV-ORD-04 | ✅ detects | ✅ detects | — |
| INV-PAY-01 | ✅ detects | ❌ not observable | `GET /payments/{orderId}` assumes at most one row exists |
| INV-SAGA-01 | ✅ detects | ✅ detects | — |
| INV-SAGA-02 | ✅ detects | ✅ detects | — |
| INV-SAGA-03 | ✅ detects | ✅ detects | — (needs the detail endpoint's `createdAt`, not the list endpoint) |
| INV-SAGA-04 | ✅ detects | ✅ detects | — |
| INV-SAGA-05 | ✅ detects | ✅ detects | — |
| INV-DSP-01 | ✅ detects | ✅ detects | — |
| INV-BOX-01 | ✅ detects | ❌ not observable | `outbox` is pure internal infrastructure, never exposed |

**12 of 15 (80%) are observable outside-in; 3 of 15 (20%) are structurally invisible to it.** This
is a smaller gap than ARCHITECTURE.md §13's original single-endpoint illustration implied — a
checker willing to call *every* service's already-public API, not just the one the client under
test would naturally call, recovers most of the coverage. The three genuine gaps
(`INV-INV-03`, `INV-PAY-01`, `INV-BOX-01`) share a common shape: each depends on data that is
either pure internal bookkeeping (`stock_adjustments`, `outbox`) or that the API's own contract
already assumes cannot be violated (`payments.order_id`'s uniqueness). That is exactly the
argument ARCHITECTURE.md §13 makes for building an inside-out checker at all — it is now a
measurement, not an assertion.

Full detection counts (representative sample, not exhaustive per invariant) verified via
`OutsideInCoverageTest` against `MockRestServiceServer` stubs built from the real controller/DTO
shapes; the full 15-row classification above reflects the actual `checkOutsideIn` implementation
for every invariant (the three "not observable" rows are the interface's default method — no
override exists, by construction, not by omission).

### Live verification

`docker compose up -d --build` (all 9 containers, including the new `conveyor-verifier` sidecar) →
seeded → four real orders placed over the public API: two happy-path (`SKU-0001` ×2, `SKU-0002` ×1),
one more (`SKU-0003` ×1), and one deliberately over-ordered (`SKU-0004` ×999999) to force a live
insufficient-stock compensation. All confirmed/cancelled as expected — inventory-service's
`GET /inventory/SKU-0001` showed `onHand` dropped from 30 to 28 and the reservation's status was
`COMMITTED` (BUG-0021's fix, confirmed live, not just in a test). `docker compose run --rm -e
SPRING_PROFILES_ACTIVE=oneshot conveyor-verifier` (the one-shot mode — `make invariant-check` on a
host with GNU Make; this session's host had none, so the equivalent command was run directly) then
reported **15/15 invariants clean, exit 0**, both immediately after the happy-path orders and again
after the forced compensation — a real, mixed-outcome system, not an empty database.

Three real bugs were found and fixed getting the sidecar itself to run, none of which the
Testcontainers/mocked-HTTP unit test suite could have caught (see `BUGS.md` for full detail):
**BUG-0022** (`ServiceClients` was `@Configuration` with two ambiguous constructors — CGLIB proxying
broke live bean instantiation; fixed by making it a plain `@Component` with the production
constructor marked `@Autowired`), a follow-on to the same bug (Spring Boot's own
`DataSourceAutoConfiguration`/`OutboxAutoConfiguration` both assume a single "primary" datasource
that a five-database service structurally doesn't have — fixed by excluding both, plus
`DataSourceTransactionManagerAutoConfiguration`/`JdbcTemplateAutoConfiguration`), and **BUG-0024**
(the report volume's mountpoint was created root-owned before the non-root container user could
claim it, so every `ViolationReportWriter` file write silently failed with
`AccessDeniedException` even though the stdout/metrics half of the same report worked fine — fixed
by creating and `chown`ing the directory in the Dockerfile before switching users).

**30-minute soak**: `scripts/soak-check.sh` polled `conveyor_verifier_clean` off
`/actuator/prometheus` every 60s for 30 minutes against the live stack (with the four orders above
already placed, no further writes during the soak itself). Result: **30/30 checks read `1.0`
(clean), zero non-clean readings**, 2026-09-10 23:38:51Z → 2026-09-11 00:07:55Z.

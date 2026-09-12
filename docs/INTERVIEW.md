# Interview Defence

Twenty questions this project should survive, answered with specifics — a bug number, a measured
result, a file — not generalities. Every claim below points at something in this repo a skeptical
reader can go check.

---

### 1. Why Saga, and not two-phase commit, here?

2PC needs every participant enlisted in one coordinator-driven transaction, holding locks until the
coordinator says commit — fine inside one database, brittle across five independent services and a
message broker, where "one participant is slow" becomes "everyone else is blocked holding a lock."
Saga trades atomicity for a durable log of forward steps plus a compensating action per step —
`saga_instances`/`saga_steps` (`ARCHITECTURE.md` ADR-1), no cross-service locks, ever. The cost is
real and named up front, not hidden: intermediate states are observable (an order can sit
`INVENTORY_RESERVED` for real, briefly), and compensation is application logic you write and test,
not a protocol you get for free. `docs/adr/0001` has the full tradeoff; `SagaCompensationIntegrationTest`
and the chaos matrix (`RESULTS.md` Phase 11) are the evidence it actually works, not just compiles.

### 2. What does the transactional outbox actually buy you, and what does it cost?

It buys atomicity between "the business fact is true" and "the world will eventually be told" — a
service commits its state change and an `outbox` row in one local transaction, so a crash between
"wrote the order" and "published `OrderPlaced`" cannot happen; a poller (`FOR UPDATE SKIP LOCKED`)
publishes afterward. The cost is that delivery becomes at-least-once, never exactly-once, and
publish latency is bounded by the poll interval, not instant. Phase 3's own exit criterion proves
the crash-safety claim directly: the publisher is killed mid-poll and the event still reaches Kafka
exactly once from the consumer's point of view, on restart. See question 3 for why "exactly once
from the consumer's point of view" is a specific, careful claim, not a slip.

### 3. Exactly-once — really?

No, and the project says so on purpose. Delivery is at-least-once (the outbox can republish a row
it isn't sure was acknowledged; Kafka redelivers on a rebalance). What's actually exactly-once is
**effects**: every consumer is idempotent, via an `inbox` table keyed on the event's own ID plus,
where it matters more, a business-level unique constraint (`payment_attempts.idempotency_key`,
`reservations(order_id, sku)`) that makes a duplicate write a no-op or a detectable conflict rather
than a double effect. Phase 4/5's own concurrency tests prove this isn't theoretical: the identical
`ReserveInventory`/`ChargePayment` command redelivered 3-5× concurrently produces exactly one
reservation/charge and N identical replies — the redelivery isn't prevented, its *effect* is
neutralized. "Exactly-once delivery" and "exactly-once effects" are different claims; this project
only ever makes the second one, and ADR-7 says so explicitly.

### 4. What breaks first under load, and how do you know — not guess?

`saga-orchestrator`'s `SagaReplyListener` — Spring Kafka's default `concurrency=1`, never raised.
Not guessed: Phase 12 compared two real Tempo traces (40 VUs vs. 160 VUs) and found the *same*
Kafka message consumed by two different listeners (`order-service`'s lightweight projection,
`saga-orchestrator`'s heavier state machine) within 17-133ms of each other at low load, but
1.08-1.10 **seconds** apart at high load — while every individual span's own execution time stayed
tiny (7-20ms) throughout. The gap is queueing time in front of one single-threaded consumer, not
processing time anywhere. Resource usage ruled out CPU (peak 20-39%) and the outbox interval (flat
regardless of load) first, specifically so the trace comparison wasn't reaching for the first
plausible story. `docs/LIMITATIONS.md` has the full writeup, including why it was deliberately
never fixed across three subsequent phases.

### 5. What would you do differently, starting over?

Wire the row-level locking discipline in `SagaOrchestrationService.requireSaga()` from day one,
instead of discovering the gap live via BUG-0049 in Phase 15. The bug (a Kafka reply handler
reading a saga's state through an unlocked query while a concurrent timeout-sweep transaction held
a real Postgres lock on the same row) is exactly the kind of "two writers, one row, no explicit
mutual exclusion" mistake a first design pass should rule out structurally — every other saga
write path already went through a locked read after Phase 6's own design, this one path was missed
until real concurrent load exposed it. Second: raise `SagaReplyListener`'s concurrency in Phase 12
itself rather than deliberately deferring it three times — the deferrals were each individually
well-reasoned (don't contaminate the measurement you're currently taking), but stacking three of
them is a real, load-bearing gap left in a "production-ready" system, not just an academic footnote.

### 6. Why orchestration over choreography?

Both are legitimate (`docs/adr/0001`); orchestration was chosen for a queryable, durable log the
chaos and invariant phases could score against (`saga_steps`, one row per attempted step,
compensation derived from that log rather than hardcoded — `abortFrom`'s own Javadoc), and because
it's the direct structural analogue to a 2PC coordinator, which is the project's own stated
throughline. Choreography's advantage (no single coordinator, less coupling) was judged not worth
losing that analogue and that queryability for a five-service system this size — it would look
different, and arguably worse, at fifteen services.

### 7. How do you know a saga can't get stuck forever?

`SagaTimeoutSweeper` runs every 5s, claims any saga past its `deadline_at` via `FOR UPDATE SKIP
LOCKED` (safe for multiple orchestrator replicas — proven by `SagaConcurrentSweepIntegrationTest`,
two threads sweeping the same 10-saga backlog concurrently, no saga double-driven), and applies
`ARCHITECTURE.md` §7.4's timeout policy per state: compensate, retry with backoff, or escalate to
`NEEDS_INTERVENTION` after `max-compensation-attempts` — never silently drop it. `NEEDS_INTERVENTION`
is itself alertable (`conveyor_saga_needs_intervention`, a real Prometheus alert rule since Phase
9) and operator-recoverable (`POST /sagas/{id}/retry`, tested in
`SagaTimeoutIntegrationTest#compensationTimeoutRetriesWithBackoffThenEscalatesAndRetryEndpointRedrivesIt`).
The one gap: `NEEDS_INTERVENTION` publishes no Kafka event, so the dashboard has no live push for
it today, only `GET /sagas?stuck=true` polling — named in `docs/LIMITATIONS.md`/CONTEXT.md's Next
Steps, not hidden.

### 8. What happens if the orchestrator crashes mid-saga?

Recovery is the *same code path* as normal timeout handling, not a separate routine
(`ARCHITECTURE.md` §7.4's own stated design property) — a crashed orchestrator's in-flight sagas
simply age past their deadline and get picked up by the sweeper (any replica) on the next tick. A
literal JVM-halt-and-restart race — the orchestrator crashes and a reply that was already in flight
arrives just as the sweeper independently times the same saga out — is exactly what Phase 15 found
live (BUG-0049): the reply handler could read stale pre-timeout state and silently overwrite the
sweep's own compensation. Fixed with a real Postgres row lock so the two paths can never
interleave; proven with a deterministic test that holds one transaction open on a timer while the
other attempts to run concurrently — not a hope that a live re-run would hit the same
few-hundred-millisecond window again.

### 9. How do you prevent overselling inventory under concurrency?

One guarded conditional `UPDATE`, not `SELECT ... FOR UPDATE` then a check-then-act:
`UPDATE stock_items SET reserved = reserved + :qty WHERE on_hand - reserved >= :qty`, backed by a
table `CHECK (on_hand - reserved >= 0)` constraint (ADR-9). Zero rows affected means insufficient
stock — the invariant is enforced by the database itself, structurally, not by application logic
that could have a gap. Phase 4's own exit criterion: 50 threads racing to reserve the last unit of
one SKU, 20 repetitions, exactly one winner every single time — not "usually," every time, because
oversell is impossible by construction rather than merely detected after the fact.

### 10. Why self-issued JWT instead of a real identity provider (Cognito/Auth0)?

A real IdP would add setup and an external dependency for a demo without exercising the part
actually worth showing — RS256 token issuance, JWKS-based validation as a Spring Security resource
server, and (the more interesting engineering problem) what browser `EventSource` cannot do:
attach an `Authorization` header, which is why the dashboard's SSE client is a hand-written `fetch`
+ `ReadableStream` frame parser instead (ADR-5, ADR-10, a direct reuse of prior streaming-client
work). The tradeoff this doesn't solve: no real user directory, password reset flow, or MFA — all
named as out of scope for an ops-dashboard demo, not overlooked.

### 11. Why SSE and not WebSocket for the live dashboard?

Plain HTTP (no upgrade handshake to fight through an ALB/CloudFront), built-in reconnect via
`Last-Event-ID` the browser handles natively, and the traffic is genuinely one-directional
(server → dashboard) — WebSocket's bidirectionality buys nothing here. The harder problem SSE
creates: with *n* `order-service` replicas behind a load balancer, a client connected to replica A
must still see an event a different replica consumed. Solved with a unique, ephemeral Kafka
consumer group per replica (`sse-fanout-${POD_NAME}`, ADR-10) so every replica sees every
dashboard-relevant event independently — the cost is *n*× read amplification on small topics, an
accepted, named tradeoff, not an oversight.

### 12. What is the invariant checker actually checking, and how do you know it isn't just checking for bugs it happens to already know about?

15 named invariants (`docs/INVARIANTS.md`), each checked two structurally different ways: inside-out
(direct DB reads, the deployed authority) and outside-in (REST-only, what an external auditor could
see). The soundness proof isn't "it passed on real traffic" — it's a **negative control**:
`InvariantSoundnessTest` constructs the actual violating state for all 15 (dropping a DB constraint
first for the two invariants a constraint would otherwise prevent constructing at all) and asserts
the checker flags the correct ID every time. A checker that never fails on a clean system could just
be broken; this one is proven to fail loudly on a broken one, on purpose, first. Precision is
checked the other way: 500 independent clean lifecycles, zero false positives.

### 13. How do you know the chaos harness's own results weren't artifacts of the harness, not the system?

They were, twice, and it's on record rather than hidden: BUG-0025 found and fixed three real bugs
in `chaos/run_matrix.py` itself (recreating all five services instead of the one under test —
which fabricated one false finding; missing `docker compose ps -a`, making crash detection blind
100% of the time; misclassifying an `HTTPError` as a dropped connection) *before* trusting any
result from it. The canonical run's own 9 non-clean trials (out of 69) were then each individually
re-verified live — querying the actual affected resource well after the trial's bounded check
window — to confirm they converged correctly on an independently slower, but still correct,
timeline, rather than being waved away as "probably fine." The true verified compensation-
correctness rate is 60/60 (100%) among fairly-timed trials, and that qualifier is stated plainly,
not smoothed into a bare "100%."

### 14. What's the single most dangerous failure mode this project found, and how was it fixed?

"Money moved, nobody told" — a customer genuinely charged (or an order genuinely confirmed) while
the order-facing system of record shows something else — found in **three independent forms**
across three phases, each a different mechanism, not the same bug rediscovered: BUG-0027 (Phase 11,
a late `PaymentCharged` reply arriving after a saga had already aborted, silently dropped instead
of triggering a refund), BUG-0036 (Phase 13, the same class of gap but mid-compensation rather than
post-abort, found by a live HPA load test), and BUG-0049 (Phase 15, a genuine concurrency race
between a timeout sweep and a reply handler, not a late-arrival ordering issue at all). Each has its
own root cause, its own fix, and its own regression test — the pattern recurring in different
mechanisms is itself the finding worth naming: distributed systems have more than one way to reach
the same dangerous state, and finding one doesn't mean the class is closed.

### 15. Why JSON Schema instead of Avro + a Schema Registry?

CI already enforces the property a registry would enforce at runtime — a compatibility gate diffs
every event schema against `main` on every push (ADR-6) — with nothing extra to run, secure, or
operate. Avro's compact binary encoding isn't worth its operational cost at this project's actual
throughput (Phase 12 measured single-digit-millisecond HTTP response times through the knee; wire
size was never the bottleneck). Migrating one topic to Apicurio + Avro was named an explicit,
optional Phase 16 stretch and was **cut**, deliberately, rather than built — the 15 phases before it
already deliver this project's real thesis without touching wire format (`docs/adr/0006`'s own
reconciliation note has the reasoning).

### 16. How is autoscaling actually measured here, and what doesn't it cover?

Two real HPAs on a real multi-node kind cluster: `order-service` on CPU, `saga-orchestrator` on a
custom application metric (`conveyor_saga_active`, via a new in-cluster Prometheus + adapter) —
deliberately two different kinds of signal. Both demonstrably scale up under load and back down
after (Phase 15, `RESULTS.md`), with scale-up latency decomposed rather than quoted as one number:
metric-scrape/HPA-sync delay dominates normally (~15-30s); pod-startup only dominates under
concurrent replica-fan-out contention, and that's a real, separately-measured exception (BUG-0047),
not a guess. What it does not cover, named up front in `docs/LIMITATIONS.md` before the phase
closed rather than after: **node-level** cluster autoscaling (provisioning a new cloud VM when
every existing node is full) — a claim about acquiring physical capacity from a cloud provider,
which this project's zero-cost constraint (ADR-13) never provisions.

### 17. What's the biggest bug this project found, and what does it say about the testing strategy?

BUG-0019 (Phase 9): the MDC enrichment interceptor that stamps `traceId`/`sagaId`/`orderId` onto
every Kafka-listener-driven log line had **never actually been wired into any listener container
since Phase 1** — a generics-invariance mismatch in how Spring Boot looks up the interceptor bean
meant it silently never matched, for eight phases, undetected by any unit or integration test,
because none of them asserted on log *content*. It was only caught because Phase 9 added the
project's first business-event INFO logs and a human looked at real log output during live
verification. The lesson this project draws from it, applied ever since: `./mvnw verify` green
proves the code compiles and the assertions written so far hold — it does not prove the system
behaves as documented in a way nobody thought to assert. Live verification against a real running
stack, not just a green test suite, is why Phases 9 through 15 each found real bugs the test suite
alone never would have (BUG-0021, BUG-0026/27/36/49, BUG-0047, and more).

### 18. If this had to go to production tomorrow, what's the punch list?

In priority order, all named in `docs/LIMITATIONS.md`: (1) raise `SagaReplyListener`'s concurrency —
one line, already measured twice as the throughput ceiling; (2) a real payment gateway integration,
not the deterministic mock; (3) HA for every datastore — Postgres replication/failover, a real
Kafka replication factor beyond 1, Mongo beyond a single replica set; (4) real secrets management
(Vault/KMS), not committed dev-literal credentials; (5) resolve the refresh-token cross-origin gap —
same-origin deployment or real TLS + `SameSite=None`; (6) actually run the already-written, already
`plan`-validated Terraform against a real AWS account and verify what changes under real cloud
network/IAM conditions, node-level autoscaling included.

### 19. Why kind/k3d locally instead of a real AWS EKS deployment?

The human's explicit instruction (ADR-13) was stricter than the original cost-*aware* plan
(time-boxed EKS sessions, ~$2/6h): spend nothing, cut scope before spending. The HPA control loop,
the scheduler, and kubelet all behave identically regardless of what's underneath a node — a kind
node, a k3d node, or a real cloud VM — so everything about Kubernetes' own control-plane behavior
this project claims (Phases 13-15) transfers directly. What doesn't transfer, named rather than
implied to: real cloud network/IAM/storage characteristics and node-level autoscaling (question 16,
`docs/LIMITATIONS.md`). Terraform for the real AWS shape is written and CI-`plan`-validated on
every push, proving it stays correct without ever spending a cent to prove it.

### 20. How do you know your own tests aren't just testing the mocks?

By minimizing mocks in the suites that matter most. Every integration test in this codebase runs
against real Postgres, real MongoDB, and real Redpanda via Testcontainers (`AbstractIntegrationTest`,
shared across all five services since Phase 1) — not an in-memory fake. The `e2e` module drives the
*public REST API only*, against a real Docker Compose (or, separately, a real kind cluster —
`KindE2ESmokeTest`) stack, the same path a real client uses. The Playwright suite (Phase 8) runs
zero mocks against the live backend. The one deliberate exception — `MockPaymentGateway` — exists
specifically *because* a real gateway's failure modes need to be reproducible on demand for chaos
testing (the chaos matrix and the three "money moved, nobody told" bugs in question 14 would be far
weaker evidence without a deterministic-under-seed way to force a decline/timeout/error on command);
it is named as a mock, its own limitation is documented (`docs/LIMITATIONS.md`), and every
idempotency/no-double-charge guarantee
tested against it is a guarantee about this project's own code, not about the gateway, so it
transfers to a real integration unchanged.

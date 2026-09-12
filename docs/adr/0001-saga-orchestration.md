# ADR-1: Saga style — orchestration, in a dedicated `saga-orchestrator` service

**Status:** Accepted (2026-09-10)

**Context:** Kafka carries the order lifecycle; a failure anywhere must
compensate every step already applied. Choreography (services react to each
other's events) and orchestration (a coordinator drives the saga) are both
legitimate; the brief flagged this as an open decision.

**Decision:** Orchestration, via a fifth service (`saga-orchestrator`) with a
durable saga log — not embedded in Order Service.

**Consequences:** Adds a service beyond the brief's four; two state machines
(saga state, order status) must agree, mitigated by a single-writer rule and
checked by INV-SAGA-05. In exchange: a queryable saga log the chaos phase can
score against, timeouts with somewhere to live, and a direct analogue to
Anvil's 2PC coordinator for the project's central comparison.

**Reconciled (Phase 16):** the "single-writer rule" this ADR names as the mitigation for two state
machines needing to agree was never actually *enforced* until Phase 15 — `saga-orchestrator`'s
timeout sweeper and its Kafka reply handlers both read/wrote `saga_instances` through paths that
could genuinely interleave under real concurrent load (BUG-0049), letting a late reply silently
overwrite a sweep's own `ABORTED` transition. The rule as originally *stated* was correct; what was
missing was a real Postgres row lock making concurrent writers actually serialize, not just an
informal convention. Fixed (`SagaInstanceRepository#findByOrderIdForUpdate`, a blocking
`PESSIMISTIC_WRITE` lock) and regression-tested — see `BUGS.md` BUG-0049 and `RESULTS.md`'s Phase 15
section. The decision itself (orchestration, a durable saga log) was never in question; its
concurrency-safety claim needed a real lock behind it, found only once real load exercised the gap.

**Full reasoning:** `ARCHITECTURE.md` ADR-1.

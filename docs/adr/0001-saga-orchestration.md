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

**Full reasoning:** `ARCHITECTURE.md` ADR-1.

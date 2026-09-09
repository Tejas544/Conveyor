# ADR-7: Transactional outbox for every state-change-plus-publish

**Status:** Accepted (2026-09-10)

**Context:** A service must never write its database and publish to Kafka as
two independent operations — a crash between them either loses the event or
duplicates the intent, and this is the direct service-layer form of Anvil's
ack-before-fsync class of bug.

**Decision:** Every service writes an `outbox` row in the same local
transaction as its business change; a poller (`FOR UPDATE SKIP LOCKED`)
publishes and marks rows sent.

**Consequences:** At-least-once delivery is the honest contract, so every
consumer is idempotent via an `inbox` table plus business-level unique
constraints — exactly-once *effects*, not exactly-once delivery. CDC
(Debezium) was rejected as unnecessary operational weight for the property a
poller already achieves at this scale.

**Full reasoning:** `ARCHITECTURE.md` ADR-7, §9.

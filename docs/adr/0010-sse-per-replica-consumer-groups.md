# ADR-10: SSE for the dashboard, with a unique ephemeral consumer group per replica

**Status:** Accepted (2026-09-10)

**Context:** The dashboard needs server-push updates; with *n* Order Service
replicas behind a load balancer, a client on replica A must still see events
consumed by replica B.

**Decision:** SSE over WebSocket (plain HTTP, built-in reconnect via
`Last-Event-ID`, no upgrade handling through ALB/CloudFront). Each replica
joins a unique, ephemeral Kafka consumer group
(`sse-fanout-${POD_NAME}`, `auto.offset.reset=latest`) so every replica sees
every dashboard-relevant event and fans out to its own connected clients.

**Consequences:** No Redis pub/sub needed. Cost: *n*× read amplification on
the (small) dashboard topics, and abandoned consumer groups that Kafka
expires via `offsets.retention.minutes` — an accepted tradeoff.

**Full reasoning:** `ARCHITECTURE.md` ADR-10.

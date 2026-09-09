# ADR-2: Redpanda for local/CI dev, real Apache Kafka in a required compatibility job

**Status:** Accepted (2026-09-10)

**Context:** A Testcontainers-heavy suite pays broker startup cost on every
run. Real Kafka is the production target; Redpanda is wire-compatible and
much faster to boot.

**Decision:** Redpanda is the default broker everywhere in the dev/test loop
(`docker-compose.yml`, `AbstractIntegrationTest`). A separate CI job
(`kafka-compat`, `.github/workflows/build.yml`) re-runs the same suites
against real Apache Kafka so compatibility is tested, not assumed.

**Consequences:** Faster everyday iteration. Any Redpanda/Kafka divergence
(transactions, rebalance timing, compaction semantics) surfaces as a required
CI failure and a `BUGS.md` entry, not a silent gap.

**Full reasoning:** `ARCHITECTURE.md` ADR-2.

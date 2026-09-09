# ADR-3: MongoDB Atlas M0 (free tier) in the deployed environment

**Status:** Accepted (2026-09-10)

**Context:** MongoDB hosting options were Atlas, AWS DocumentDB, or
self-hosting on EKS. ADR-13 later made $0 the hard constraint for the whole
deployment; this decision was already aligned with that before ADR-13 existed.

**Decision:** Atlas M0 (free, indefinitely, 512 MB shared). Local dev uses the
official `mongo` Docker image.

**Consequences:** No VPC peering on M0 — public endpoint with TLS+SCRAM and an
IP allowlist; bounded connection pool (`maxPoolSize`) required given the
500-connection cap. DocumentDB was rejected on cost (no free tier, ~$57/mo
minimum) and on being a partial API emulation rather than real MongoDB.

**Full reasoning:** `ARCHITECTURE.md` ADR-3.

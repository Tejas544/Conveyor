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

**Reconciled (Phase 16):** Atlas M0 was never actually provisioned. ADR-13 (written after this one)
made the *executed* deployment target local/ephemeral Kubernetes (kind), not a persistent cloud
environment — so "the deployed environment" this ADR was written for never materialized in a form
that needed a real managed Mongo instance. Every environment this project actually ran in, local
dev through Phase 14's CI-deployed kind cluster, used a containerized `mongo` image (a StatefulSet in
`infra/helm/conveyor`, Phase 13). This is not a reversal of the decision — Atlas M0 remains the
right choice *if* this project were ever deployed somewhere persistent — it is simply a decision
this project's actual $0/ephemeral path never had occasion to exercise, stated plainly rather than
left to look executed when it wasn't.

**Full reasoning:** `ARCHITECTURE.md` ADR-3.

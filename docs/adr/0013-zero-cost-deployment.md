# ADR-13: Zero-cost deployment target — no real AWS EKS/RDS is ever provisioned

**Status:** Accepted (2026-09-10), added at the human's explicit instruction

**Context:** The original plan (Phases 14–15) was cost-*aware* — time-boxed
EKS sessions, ~$2 for six hours, a teardown script. The human's actual
instruction was stricter: spend nothing, cut scope before spending.

**Decision:** Terminal deployment target is local/ephemeral Kubernetes
(kind/k3d), GHCR instead of ECR, Vercel/Cloudflare Pages instead of
S3+CloudFront, containerized Postgres instead of RDS. Atlas M0 is kept — it
was already free. Terraform for the original AWS design is written and
`plan`/`validate`-checked in CI but never `apply`'d.

**Consequences — the one named limitation:** node-level cluster autoscaling
(Karpenter/Cluster Autoscaler provisioning real cloud nodes) cannot be
demonstrated without a real cloud account and is recorded as tested-absent in
`docs/LIMITATIONS.md` (Phase 16), not hidden. Everything else — the Saga/2PC
thesis, all five services, the dashboard, and the entire rigor stage — was
never AWS-dependent and is unaffected. The original costed AWS path remains
on record as a documented, not-executed fallback.

**Full reasoning:** `ARCHITECTURE.md` ADR-13, §15.

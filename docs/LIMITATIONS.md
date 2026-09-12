# Limitations

This document exists per `PLAN.md` Phase 15's own requirement: the node-level-autoscaling
limitation below must be written down **before** Phase 15 is marked complete, not deferred to
Phase 16's documentation pass. Phase 16 will likely add more entries here (`CLAUDE.md` §1's
`docs/LIMITATIONS.md` deliverable, "what this system does not do and what would break in
production"); this file starts with the one limitation Phase 15 itself is required to name.

## Node-level cluster autoscaling is not measured

**What is measured (Phase 15, `RESULTS.md`).** Pod-level Horizontal Pod Autoscaling — the control
loop that changes a Deployment's *replica count* in response to load — is fully and honestly
measured against a real, multi-node Kubernetes control plane (a 3-node kind cluster: 1
control-plane + 2 workers). Two different kinds of autoscaler are exercised: a CPU-driven HPA on
`order-service` and a custom-application-metric HPA (`conveyor_saga_active`, via
prometheus-adapter's external metrics API) on `saga-orchestrator`. Scale-up latency is measured
and decomposed, not quoted as a single number. This behavior is identical regardless of what kind
of machine sits underneath any given node — a kind node, a k3d node, or a real cloud VM all run
the same kubelet, the same HPA controller, and the same scheduler.

**What is *not* measured: node-level (cluster) autoscaling.** In a real cloud deployment, if every
existing node is already full, a **Cluster Autoscaler** (or, on EKS, Karpenter) provisions a *new
node* — a new EC2 instance — bootstraps it, and only then can the scheduler place the pod the HPA
already decided it wanted. That is a fundamentally different claim from anything demonstrated
here: it is a claim about **acquiring physical compute capacity from a cloud provider**, not a
claim about Kubernetes' own control loops. This project deliberately provisions no cloud compute
at all (ADR-13, the zero-cost deployment target) — the kind cluster's three "nodes" are containers
on one Docker host with a fixed total resource ceiling, and nothing in this project's own code or
infrastructure ever asks a cloud provider for another one.

**Why this is named rather than glossed over.** It would be easy to let "the HPA scaled a pod up"
quietly stand in for "this system autoscales," when the two are not the same claim, and the
second is the one a production reader will actually care about under real capacity pressure. If
this project's kind cluster ever ran genuinely out of room (all three nodes full), the honest,
verifiable behavior is that new pods stay `Pending` — that is the correct, expected outcome of
*not* running a cluster autoscaler, not a bug to hide.

**What would be needed to close this gap for real.** `infra/terraform/` (Phase 14) already
provisions an EKS cluster with an IRSA role scaffolded for the AWS Load Balancer Controller, but
carries no Cluster Autoscaler / Karpenter IAM role or manifests, and — per ADR-13 — `terraform
apply` never runs anywhere in this project's CI or by a human's hand without a separate, explicit
go-ahead. Measuring node-level autoscaling for real would mean: provisioning that EKS cluster (a
real, billable AWS resource, named and time-boxed per `CLAUDE.md` §9 before creation), installing
Karpenter or Cluster Autoscaler with real node-group/NodePool IAM permissions, driving load past
the *node* capacity ceiling (not just the pod-count ceiling this phase's HPAs already hit), and
measuring the same kind of scale-up-latency decomposition this phase measured for pods — except
with "new EC2 instance provisioned and joined to the cluster" as an extra stage before
"pod-scheduled" can even begin. Nothing about that is technically hard; it is simply a different,
costed claim this project's own zero-cost constraint puts out of scope by design, not by oversight.

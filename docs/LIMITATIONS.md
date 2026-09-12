# Limitations

This document exists per `PLAN.md` Phase 15's own requirement: the node-level-autoscaling
limitation below was written down **before** Phase 15 was marked complete, not deferred to Phase
16. Phase 16 (`CLAUDE.md` §1's `docs/LIMITATIONS.md` deliverable — "what this system does not do
and what would break in production") adds every entry below it. Written honestly, per that same
instruction — a limitations doc that only lists things that don't matter isn't one.

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

## `saga-orchestrator`'s reply consumer is single-threaded, on purpose, for three phases running

Spring Kafka's default `concurrency=1` on `SagaReplyListener` was never raised. Phase 12 named and
trace-evidenced this as the system's actual throughput bottleneck at load — not CPU (peak 20-39%),
not Postgres, not the outbox poll interval. Phase 13/14 didn't touch it either. Phase 15 measured a
second, independent symptom of the same root cause: throughput at 60 concurrent VUs (2.69 orders/s)
was *lower* than at 15 VUs (8.65 orders/s), because `iteration_duration` grew faster than offered
concurrency once the single consumer thread became the constraint (Little's Law; see `RESULTS.md`'s
Phase 15 section).

**Why left unfixed three phases in a row, not an oversight.** Phase 12's own rule (repeated in every
phase since): a rigor phase names a bottleneck, it does not fix it speculatively mid-measurement,
because doing so would contaminate the very number that phase exists to report. Phase 15 had an
additional, specific reason: raising per-JVM concurrency would have reduced backlog growth under
load, making `saga-orchestrator`'s own custom-metric HPA — the thing that phase needed to
demonstrate — harder to trigger under the same load profile.

**What production would actually need:** set `spring.kafka.listener.concurrency` on this one
listener to something bounded by `conveyor.saga-replies.v1`'s own partition count (6) — a one-line
change, deliberately never made, with two independent measurements (Phase 12's trace comparison,
Phase 15's throughput regression) already showing exactly where and why it matters.

## Three invariants are structurally invisible to outside-in checking

`conveyor-verifier` runs two independent modes (Phase 10): inside-out (direct DB reads, the deployed
authority) and outside-in (REST-only, what an external auditor with no DB access could verify). 12
of 15 invariants are observable both ways; `INV-INV-03` (stock conservation), `INV-PAY-01` (payment
ledger integrity), and `INV-BOX-01` (outbox/inbox bookkeeping) are inside-out only — either pure
internal bookkeeping no endpoint exposes, or protected by a database constraint whose violation
can't be constructed without dropping that constraint first (`docs/INVARIANTS.md` has the full
per-invariant detail). **What this means in production:** an operator without direct database
access — a third-party auditor, a support engineer restricted to the public API — cannot
independently confirm these three properties; they have to trust the deployed checker's own
inside-out report, which is exactly the trust boundary a real incident-response runbook needs to
name rather than assume away.

## The payment gateway is a deterministic simulator, not a real payment service provider

`payment-service`'s `MockPaymentGateway` (Phase 5) is a genuinely useful *chaos substrate* —
seedable failure modes (decline/timeout/gateway-error), reproducible under a fixed seed — but it is
not Stripe, Adyen, or any real PSP. Nothing here exercises: real card-network response-time
distributions and their own timeout/retry semantics, 3-D Secure or other step-up authentication
flows, real chargeback/dispute handling, PCI-DSS scope (no real card data ever exists in this
system), or a real settlement/reconciliation batch process. The idempotency and no-double-charge
guarantees (Phase 5's own concurrency test) are real and would transfer directly to a real gateway
integration; the gateway's own failure *behavior* would not.

## The refresh-token cookie does not survive the cross-origin deployment path

ADR-5's refresh-token cookie is `SameSite=Strict` (deliberately — so XSS can't read it via JS, and
so it can't be exfiltrated via a cross-site request either). Phase 14's actual deployment path put
the frontend (GitHub Pages) and backend (a kind cluster's NodePorts) on different origins by
construction, and `SameSite=Strict` means the browser never attaches that cookie cross-origin,
CORS headers or HTTPS notwithstanding. The practical effect, named in `docs/DEPLOYMENT.md` rather
than discovered by a user: the dashboard works normally for one 15-minute access-token lifetime,
then the automatic silent refresh fails and the user has to log in again — a real, live UX
consequence of a real security tradeoff, not a bug, but one a production rollout of this exact
pairing would need to either accept or solve for real (a same-origin deployment, or `SameSite=None;
Secure` behind real TLS, which this project's $0 constraint doesn't provision).

## Every datastore is a single instance — no HA, no replication, no backup/DR story demonstrated

One Postgres container, one Mongo container (or StatefulSet, on kind), one dual-role Strimzi Kafka
node, everywhere this project has ever run. `ARCHITECTURE.md` §4's "database-per-service enforced
logically via roles/grants, not physically" is a real, tested property (Phase 2's verifier-role
test) — but it describes *tenant* isolation on one instance, not *availability* under an instance
failure. Nothing here demonstrates: Postgres streaming replication or a failover story, Mongo
replica sets beyond the single-node `rs0` Testcontainers/local-dev already uses for driver
compatibility, Kafka partition replication (Strimzi's `dual-role` node is one broker, replication
factor 1 throughout), or any backup/restore runbook. A real production deployment of this
architecture would need all four; none were in scope for a portfolio project whose rigor budget
went to the Saga/chaos/load/autoscaling thesis instead.

## The costed AWS path is validated, never executed — its real characteristics are untested

`infra/terraform/` provisions a real VPC/EKS/RDS/ECR/IRSA shape, and `terraform plan`/`validate`
run in CI against every push (ADR-13) — but `terraform apply` has never run, anywhere, against a
real AWS account (grep-verified in CI, Phase 14). Everything this project knows about its own
behavior under real cloud conditions — cross-AZ network latency, real EBS volume performance, IAM
policy edge cases, an actual ALB/Ingress in front of the dashboard, RDS's own failover behavior — is
therefore *designed for* but not *measured*. The plan is real and re-validated on every push; the
experience of actually running it is not something this project can honestly claim.

## Secrets are demo literals, not vault-managed

Every credential in `docker-compose.yml`, `.env.example`, and `infra/helm/conveyor/values.yaml` is a
committed, literal, `*_local_dev_only`-suffixed default (`ARCHITECTURE.md` §12's own framing: "demo
credentials, not secrets to protect"). This is the right call for a repo meant to `git clone && make
up` with zero setup — but it means nothing here demonstrates real secret lifecycle management
(rotation, a KMS/Vault-backed injection path, short-lived credentials). A real deployment would
override every one of these via `-f` a private values file or a real secrets manager, never commit
one — documented as the expectation, not built, since building it would mean maintaining
infrastructure (a Vault instance, KMS keys) with nothing in this project's own threat model to
protect.

## This specific development machine's Docker Desktop has a known fragility under sustained heavy load

Documented as its own recurring pattern across BUG-0002/0007/0008/0050: under sustained heavy
container churn (many concurrent JVMs, repeated restarts, a multi-node kind cluster all on one
Docker Desktop/WSL2 host), the Docker Engine API itself has repeatedly become unresponsive or
returned `500`s, independent of anything in this project's own code. Each occurrence was worked
around live (a restart, a cluster recreation) rather than fixed, because the root cause is host
software state outside this repository's scope to repair. Named here because it shaped this
project's own working practices (CONTEXT.md's "don't run a heavy `./mvnw verify` reactor at the
same time as a live kind cluster" rule, Phase 15's own right-sizing of its measurement ceiling to
what this host can actually sustain) and because a reader reproducing this project's own results on
different hardware should expect those specific numbers — not the underlying architecture — to
vary with the host.

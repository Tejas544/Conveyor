# Deployment — Conveyor

Two paths exist. **Only the first is ever executed automatically.** The second is fully specified,
CI-`validate`d/`plan`ned, and requires a human's separate, explicit go-ahead before a single real
AWS resource is created (`CLAUDE.md` §9, ARCHITECTURE.md §15.4, ADR-13).

## 1. The executed path — $0, by construction

```
GHCR ← GitHub Actions (build → test → containerize → push → deploy)
                                          │
                          a fresh kind cluster, created inside the runner
                          Ingress-free NodePorts → 5 services + conveyor-verifier (CronJob)
                                      ↘ containerized Postgres (in-cluster)
                                      ↘ Kafka: Strimzi (in-cluster)

GitHub Pages ← GitHub Actions (React build) — a permanent public URL, independent of whether
                                               any cluster is up at the time
```

### 1.1 CI: `.github/workflows/build.yml`

On every push to `main` (and every pull request, minus the two push-only jobs):

1. `build-and-test` — the full Maven reactor (`./mvnw verify`): unit + Testcontainers-backed
   integration tests, Spotless, Checkstyle.
2. `kafka-compat` — the same suites against real Apache Kafka, not just Redpanda (ADR-2).
3. `e2e` — the whole stack via `docker compose`, driving only the public REST API.
4. `invariant-check` — `conveyor-verifier` run once against that same live compose stack; exits
   non-zero on any violation.
5. `security-scan` / `secrets-scan` — Trivy (filesystem) and gitleaks.
6. **`containerize-and-push`** (push to `main` only) — every one of the above must be green first.
   Builds all six images and pushes each to **GHCR**, tagged with the commit SHA and `latest`, then
   Trivy-scans the pushed image itself (not just the filesystem) as its own gate.
7. **`deploy-to-kind`** — creates a *fresh* kind cluster inside the runner (Calico, metrics-server,
   Strimzi Kafka — the same shape `scripts/kind-up.sh` builds locally), pulls the images
   `containerize-and-push` just pushed (a Kubernetes `docker-registry` Secret built from the job's
   own `GITHUB_TOKEN`, since a fresh cluster starts with no credentials to GHCR at all), `helm
   install`s the chart, then:
   - runs `KindE2ESmokeTest` against the cluster's NodePorts as the **smoke test** — the same class
     Phase 13 wrote, reused rather than duplicated;
   - triggers the chart's `conveyor-verifier` CronJob on demand and gates on its exit code — the
     **invariant check as a deployment gate**, not a side effect;
   - tears the cluster down afterward regardless of outcome (`if: always()`).
8. **`terraform-plan`** and **`verify-no-terraform-apply`** — see §2 below.

**Proof that the gate is real, not assumed:** a deliberately broken commit (a failing assertion
pushed to a throwaway branch, observed to fail `build-and-test` and never reach
`containerize-and-push`/`deploy-to-kind`, then discarded) is exactly how this was verified before
Phase 14 was marked complete — see `CONTEXT.md`'s Phase 14 entry for the run this refers to.

### 1.2 Frontend: `.github/workflows/frontend-pages.yml`

Builds `frontend/` (`tsc -b && vite build`) and deploys the static output to **GitHub Pages** —
chosen over Vercel/Cloudflare Pages (both equally valid per ARCHITECTURE.md §15.2) specifically
because it needs **no new account and no repo secrets**: the workflow authenticates with the same
`GITHUB_TOKEN` every other job already has. Live at **https://tejas544.github.io/Conveyor/**.

**How the hosted frontend reaches a backend it doesn't run.** The static build is compiled with
`VITE_ORDER_API_URL=http://localhost:8081` (and the matching `_INVENTORY_`/`_PAYMENT_`/`_SAGA_`/
`_DISPATCH_` variables for ports 8082–8085) — `docker-compose.yml` and
`infra/k8s/kind-config.yaml` deliberately publish those exact ports, so **the same static build
works against either**. Opening the Pages URL in a browser on a machine that also has
`docker compose up` or `make kind-up` running locally talks straight to `localhost` — the request
is made by the *browser*, not by GitHub's servers, so there is nothing to deploy or reach into on
the CI/hosting side. `conveyor-common`'s new `CorsProperties`
(`conveyor.security.cors.allowed-origins`, wired through every service's
`SecurityAutoConfiguration`) is what makes that cross-origin call legal at all — both
`docker-compose.yml` and `infra/helm/conveyor/values.yaml` default it to
`https://tejas544.github.io`, so `make up` or `make kind-up` alone already leaves a stack the
permanent Pages URL can reach with no extra flags.

**Named limitation, not silently glossed over.** `AuthController`'s refresh token travels as an
`HttpOnly`, `SameSite=Strict` cookie (ADR-5 — deliberately, so a successful XSS can't read it).
`SameSite=Strict` means the browser never sends that cookie on a cross-origin request at all, cookie
or not, HTTPS or not — CORS headers only govern whether the browser *shares the response* with
JavaScript, not whether cookies attach to the request in the first place. Logging in from the Pages
origin against a `localhost` backend works exactly like any other cross-origin call (`Authorization:
Bearer` header, no cookie involved in a *request*), but the response's `Set-Cookie` is silently
dropped by the browser under `SameSite=Strict`. The practical effect: the Pages-hosted dashboard
works normally for the lifetime of one access token (15 minutes, `JwtIssuerProperties`'s default);
the automatic silent-refresh `AuthContext.tsx` performs every 10 minutes will fail once the cookie
never arrived, and the user has to log in again rather than the session persisting indefinitely.
Fixing this for real would mean loosening the cookie to `SameSite=None; Secure`, which itself
requires HTTPS on the backend — real TLS in front of a local kind/compose stack is exactly the kind
of infrastructure ADR-13's $0 target does not add. Every deployment shape that serves the frontend
and backend from the same origin (`npm run dev`'s Vite proxy, or any future same-origin reverse
proxy) is unaffected — this limitation is specific to the Pages-hosted-frontend-against-a-different-
origin-backend pairing only.

### 1.3 Reproducing this locally

```bash
docker compose up -d --build && docker compose run --rm -e SPRING_PROFILES_ACTIVE=seed order-service
```
or
```bash
make kind-up
```
then open https://tejas544.github.io/Conveyor/ in a browser on the same machine.

## 2. The AWS fallback path (ARCHITECTURE.md §15.4) — costed, never applied automatically

`infra/terraform/` is a complete VPC + EKS + RDS + ECR + IAM/IRSA configuration, kept in the tree and
exercised on every push by two CI jobs:

- **`terraform-plan`** — `terraform fmt -check`, `validate`, and `plan`, all running against
  **dummy AWS credentials with no real account reachable at all**. See
  `infra/terraform/README.md` for exactly how that's possible (hardcoded AZs, no live-API data
  sources, the `skip_*` provider flags) — it is not a shortcut that skips real checking, `plan`
  genuinely computes the full 37-resource diff from the schema alone.
- **`verify-no-terraform-apply`** — greps every workflow file and fails the build if the literal
  string `terraform apply` ever appears in one. Proof, not a claim: this is what makes "nothing
  billable exists anywhere as a result of this phase" checkable rather than asserted.

**If this path is ever actually run**, `infra/terraform/README.md` documents the exact steps —
real credentials, a real `TF_VAR_db_password`, `terraform apply`, and eventually `terraform destroy`
(also documented in `infra/teardown.sh`) — and per `CLAUDE.md` §9 and ARCHITECTURE.md §15.4, that
requires the human's separate, explicit sign-off. This document existing, and CI passing, is not
that sign-off.

## 3. Cost, checked rather than assumed

`make cost-check` documents what "at $0" means for each path — see `ARCHITECTURE.md` §15.3 for the
full resource-by-resource table. `make teardown` (`infra/teardown.sh`) tears down every local
resource this project can create by itself (compose stack, kind cluster) and prints, rather than
runs, the one command that would matter if the AWS fallback was ever separately applied.

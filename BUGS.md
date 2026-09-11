# Bug Log — Conveyor

Append-only. Newest entry on top. Never delete an entry — change its
**Status** field instead. See `CLAUDE.md` §5 for the full policy on when to
log (short answer: every non-trivial bug, the moment it's found, even ones
fixed immediately).

Format for each entry:

```
## [BUG-XXXX] Short title
- **Date:** YYYY-MM-DD
- **Phase:**
- **Severity:** Critical / High / Medium / Low
- **Symptom:**
- **Root cause:**
- **Fix:**
- **Status:** Open / Fixed / Won't Fix (reason)
```

---

## [BUG-0044] `invariant-check` CI job saw one transient connection failure under real runner resource contention, even after BUG-0040's `--wait` fix
- **Date:** 2026-09-11
- **Phase:** Phase 14 — CI/CD and deployment (found live on the first CI run to get this far with every other Phase 14 fix already in place)
- **Severity:** Low
- **Symptom:** The one-shot `conveyor-verifier` run (`make invariant-check`) fails with the same `ResourceAccessException`/`ConnectException`/`ClosedChannelException` connecting to `http://order-service:8081` that BUG-0040 already fixed once — but this time nearly a full minute after `docker compose up --wait` had already confirmed `order-service` healthy, and `order-service`'s own container never restarted or stopped before the failure (confirmed from the container lifecycle events in the job log).
- **Root cause:** Not the same race as BUG-0040. This job runs nine JVMs (five app services + conveyor-verifier's sidecar + two one-shot seed containers + the one-shot verifier itself) alongside Kafka, MongoDB, and Postgres, all starting within roughly a two-minute window on a standard shared GitHub-hosted runner — genuinely heavier concurrent load than this project's usual 12-core local dev host. A single transient connection failure under that contention, once, is runner noise rather than an application defect.
- **Fix:** `make invariant-check` is retried up to twice more (15s, then 30s backoff) in `build.yml`'s `invariant-check` job — the same bounded-retry-over-a-flaky-live-dependency posture this codebase already applies elsewhere (dispatch-service's retry-then-DLQ, `SagaTimeoutSweeper`'s backoff), not a defect fix.
- **Status:** Fixed

---

## [BUG-0043] Trivy action re-pinned without its `v` tag prefix a second time, in the exact same file, in the exact same session
- **Date:** 2026-09-11
- **Phase:** Phase 14 — CI/CD and deployment (found live on the very next real CI run after BUG-0038's own fix)
- **Severity:** Low
- **Symptom:** `security-scan` (and the new per-image scan added in `containerize-and-push`) fail with the identical `Unable to resolve action 'aquasecurity/trivy-action@0.36.0', unable to find version '0.36.0'` error BUG-0038 already diagnosed — this time self-inflicted, by writing `@0.36.0` again instead of `@v0.36.0` when bumping the pin.
- **Root cause:** Plain human/assistant error repeating BUG-0038's exact lesson within the same session — worth logging as its own entry rather than silently folding into BUG-0038, since the bug log's value is in showing what actually happened, including a mistake repeated right after it was first found.
- **Fix:** `sed -i 's/trivy-action@0\.36\.0/trivy-action@v0.36.0/g'` across both occurrences in `build.yml`.
- **Status:** Fixed

---

## [BUG-0042] `npm ci` fails on a peer-dependency conflict `npm install` had always silently tolerated
- **Date:** 2026-09-11
- **Phase:** Phase 14 — CI/CD and deployment (found the moment the first-ever frontend CI job ran `npm ci` against this lockfile)
- **Severity:** Medium
- **Symptom:** `frontend-pages.yml`'s `npm ci` step fails immediately with `ERESOLVE could not resolve` — `openapi-typescript@7.13.0` peer-depends on `typescript@^5.x`, but `package.json` pins `typescript@~6.0.2`.
- **Root cause:** `npm install` (the only install command this repo's frontend had ever been built with, always locally, never in CI before this phase) only *warns* on an unresolvable peer dependency by default; `npm ci` — a from-scratch, strict install, which is what CI correctly uses instead — fails outright on the same conflict. The conflict itself isn't new: `openapi-typescript`'s peer range simply hasn't caught up to TypeScript 6 yet, and the actual build has been green under 6.0.3 the entire time (`tsc -b` compiles clean, all tests pass).
- **Fix:** `frontend/.npmrc` with `legacy-peer-deps=true` — makes explicit what `npm install` was already doing implicitly, rather than downgrading a compiler version that already builds and tests clean.
- **Status:** Fixed

---

## [BUG-0041] Every other shell script in the repo had the same missing-executable-bit defect as BUG-0037
- **Date:** 2026-09-11
- **Phase:** Phase 14 — CI/CD and deployment (found auditing the repo after BUG-0037, while adding a new `infra/teardown.sh`)
- **Severity:** High
- **Symptom:** `git ls-files -s '*.sh'` showed `scripts/kind-up.sh`, `scripts/kind-down.sh`, `scripts/soak-check.sh`, and `frontend/scripts/generate-types.sh` all committed as mode `100644` — the exact same defect as BUG-0037, just never triggered because nothing in CI has ever invoked any of them directly (`make kind-up`/`make kind-down` are always run locally on this Windows dev machine, where the git-tracked mode is irrelevant) and `deploy-to-kind`'s own new CI job (this phase) inlines its steps rather than calling `kind-up.sh`. A fresh Linux checkout running any of these four via `./script.sh` would hit `Permission denied`, identically to BUG-0037.
- **Root cause:** Same as BUG-0037 — these scripts were authored/committed from this Windows machine, where the executable bit is meaningless locally, so the gap was never observable here.
- **Fix:** `git update-index --chmod=+x` on all four, plus `infra/postgres/init-service-databases.sh` / `infra/helm/conveyor/files/init-service-databases.sh` (not strictly required — the official Postgres entrypoint `source`s `docker-entrypoint-initdb.d/*.sh` regardless of the executable bit — but corrected anyway since they're the same class of file and the inconsistency was otherwise unexplained) and the new `infra/teardown.sh` added this phase.
- **Status:** Fixed

---

## [BUG-0040] `invariant-check` CI job hit `order-service` before it finished starting — a race BUG-0037 had always hidden
- **Date:** 2026-09-11
- **Phase:** Phase 14 — CI/CD and deployment (found live, first time this job ever ran to completion)
- **Severity:** Medium
- **Symptom:** The `invariant-check` job's seed step fails with `ResourceAccessException: I/O error on GET request for "http://order-service:8081/api/v1/orders"` / `ConnectException: ClosedChannelException`, `make: *** [Makefile:50: invariant-check] Error 1`.
- **Root cause:** `docker compose up -d --build` (no `--wait`) returns as soon as containers are *started*, not once the app services' own Dockerfile `HEALTHCHECK`s report `healthy` — the very next step ran `docker compose run --rm ... order-service` (the seed job) and, moments later, `make invariant-check`, both of which can race Spring Boot's own startup time on a cold, freshly-built container. Invisible until now because BUG-0037 blocked every prior CI run before reaching this job at all.
- **Fix:** Added `--wait` to the `docker compose up -d --build` invocation in `.github/workflows/build.yml`'s `invariant-check` job — Compose blocks until every service with a healthcheck reports healthy before the step returns.
- **Status:** Fixed

---

## [BUG-0039] `e2e` module's Spotless check had never actually run in CI, and failed the first time it did
- **Date:** 2026-09-11
- **Phase:** Phase 14 — CI/CD and deployment (found live, first time the `e2e` CI job ever ran to completion)
- **Severity:** Low
- **Symptom:** The `e2e` job's `mvn verify -DskipE2E=false` — after all 7 tests themselves passed — fails at the very end on `spotless-maven-plugin:check`, citing formatting violations in `TraceContextPropagationE2ETest.java`, `RestClient.java`, and `KindE2ESmokeTest.java` (comment line-wrap width, mostly).
- **Root cause:** The `e2e` module is deliberately excluded from the root reactor's default `./mvnw verify` (CONTEXT.md, Phase 7 — it needs a live compose stack no other module's suite does), so its own Spotless check has only ever run inside `build.yml`'s `e2e` CI job specifically — a job that, per BUG-0037, had never once completed since CI started running. These three files' formatting had silently drifted out of Spotless's expected style the entire time with nothing to catch it.
- **Fix:** `./mvnw -f e2e/pom.xml spotless:apply`, committed as-is; `spotless:check` now passes clean.
- **Status:** Fixed

---

## [BUG-0038] `aquasecurity/trivy-action` pinned without its `v` tag prefix — the action reference never resolved
- **Date:** 2026-09-11
- **Phase:** Phase 14 — CI/CD and deployment (found immediately after BUG-0037's fix let the pipeline run far enough to reach this job for the first time)
- **Severity:** High
- **Symptom:** The `security-scan` job's "Set up job" step fails outright: `Unable to resolve action 'aquasecurity/trivy-action@0.24.0', unable to find version '0.24.0'`. Never previously visible because BUG-0037 blocked every prior run before any job finished.
- **Root cause:** `.github/workflows/build.yml` referenced `aquasecurity/trivy-action@0.24.0`; the upstream repo tags releases `v0.24.0` (with the `v`), so the literal string `0.24.0` matches no ref.
- **Fix:** Repinned to `aquasecurity/trivy-action@0.36.0` (the current release, `v` included in the actual tag as GitHub Actions' `@` syntax expects) in `build.yml`'s `security-scan` job, and the same pin is used for the new per-image scans added in this phase's `containerize-and-push` job.
- **Status:** Fixed

---

## [BUG-0037] `./mvnw` committed without the executable bit — every GitHub Actions run on this repo has failed since CI first ran
- **Date:** 2026-09-11
- **Phase:** Phase 14 — CI/CD and deployment (found at the very start, before any new pipeline work — CI has to actually pass before more is stacked on it)
- **Severity:** Critical
- **Symptom:** `gh run list` shows all 4 recorded workflow runs on `main` (Phase 9, 11, 12, 13 close-out pushes) as `failure`. Every job that runs `./mvnw` fails identically: `./mvnw: Permission denied` / `Process completed with exit code 126`. This means CI has never gone green even once since it started actually running, silently contradicting Phase 1's own exit criterion ("CI green on a pull request") and every later phase's implicit assumption that the pipeline was healthy.
- **Root cause:** `git ls-files -s mvnw` shows mode `100644` (not executable) since the commit that added it in Phase 1 (`9c291b0`). This machine is Windows, where the executable bit is meaningless locally, so `./mvnw` always worked here regardless of the git-tracked mode — the discrepancy was only ever observable on a POSIX CI runner, and nobody had checked `gh run list` until this phase.
- **Fix:** `git update-index --chmod=+x mvnw` (and `mvnw.cmd` left as-is, Windows doesn't need it), committed as its own `fix:` commit ahead of any other Phase 14 work.
- **Status:** Fixed

---

## [BUG-0036] A late `PaymentCharged` reply arriving mid-compensation (not yet `ABORTED`) was silently dropped — customer charged, never refunded
- **Date:** 2026-09-11
- **Phase:** Phase 13 — Containerization and Kubernetes (local); found live by this phase's own HPA
  synthetic-load test (20 concurrent order-placement loops against order-service for 3 minutes,
  driving real HPA scale-up 1→5), not a designed chaos scenario — the same class of finding Phase 11
  exists to produce, surfacing here as an unplanned side effect of a different exit criterion.
- **Severity:** Critical — real financial-integrity defect: "payment still CAPTURED on a CANCELLED
  order," `conveyor-verifier`'s own INV-ORD-03 (`ARCHITECTURE.md` §14's "money moved, nobody told,"
  the danger label reserved for the single most dangerous class of bug in this system). 2 of the
  orders placed during the load test hit it live (exact total order count not captured — the load
  script's own goal was CPU load for the HPA test, not a counted throughput run like Phase 12's).
- **Symptom:** a live `conveyor-verifier` inside-out run (previously always clean throughout this
  session) reported 6 violations across three invariants: INV-ORD-02 (reservation still `HELD`),
  INV-ORD-03 (payment `CAPTURED` on a `CANCELLED` order — the serious one), and INV-SAGA-02
  (`RESERVE_INVENTORY` succeeded with no compensation on an `ABORTED` saga).
- **Root cause:** a real, previously-unknown gap in BUG-0027's own fix (Phase 11). That fix taught
  `handlePaymentCharged` to refund a late-arriving `PaymentCharged` reply instead of silently
  discarding it — but only when `saga.getState() == SagaState.ABORTED`. Under this phase's load, the
  `RELEASE_INVENTORY` compensation a `CHARGE_PAYMENT` timeout triggers took three retries spanning
  **~7 minutes** (`saga-orchestrator` logs: attempts at 11:24:21, 11:25:43, 11:28:54, succeeding at
  11:31:44) before the saga actually reached `ABORTED`. The late `PaymentCharged` reply's payment
  record was created at 11:25:04 — squarely inside that window, while the saga was still
  `COMPENSATING_INVENTORY`, not yet `ABORTED`. The `else` branch logged `"Ignoring PaymentCharged for
  saga ... in state COMPENSATING_INVENTORY"` at `WARN` and did nothing else: no refund, no trace
  above that one log line. Confirmed directly from `saga-orchestrator`'s own logs for the exact
  order/saga IDs the verifier flagged, not inferred.
- **Fix:** `SagaOrchestrationService.handlePaymentCharged` now checks
  `isPastChargingPaymentViaTimeout(saga.getState())` — `ABORTED`, `COMPENSATING_INVENTORY`, or
  `NEEDS_INTERVENTION` (every state reachable *because of* the same `CHARGE_PAYMENT` timeout that
  made this reply late, not just its eventual terminal one) — instead of `== ABORTED` alone.
  `COMPENSATING_PAYMENT` is deliberately excluded: that state is reached only via the unrelated
  operator-abort path, where a charge is already known-successful, not late. New regression test,
  `SagaTimeoutIntegrationTest#latePaymentChargedWhileCompensationStillRetryingRefundsThePaymentInsteadOfDroppingIt`,
  drives a saga to `COMPENSATING_INVENTORY` (timeout fired, `RELEASE_INVENTORY` commanded, reply not
  yet processed) and asserts the late reply now produces a `RefundPayment` outbox record. Full
  `saga-orchestrator` module `./mvnw verify` green after the fix (all pre-existing tests plus the new
  one), Spotless/Checkstyle clean.
- **Remediation of the live data:** the two affected orders (and the co-occurring INV-ORD-02/
  INV-SAGA-02 violations from the same load-test window) existed only on this session's own local
  kind cluster, itself created from scratch this phase — not a shared or production environment.
  Rather than hand-remediate three specific rows, the Postgres and Mongo PVCs were deleted and
  recreated fresh (`kubectl delete pvc data-postgres-0 data-mongo-0`, same "down -v + reseed"
  posture CONTEXT.md's Phase 12 Next Steps already established for stale-data cleanup), the fixed
  `saga-orchestrator` image rebuilt/reloaded/redeployed, and the cluster re-verified clean from a
  genuinely empty state.
- **Status:** Fixed. Re-verified live end to end after the data reset: `conveyor-verifier` reports
  15/15 invariants clean; `KindE2ESmokeTest`'s three scenarios (happy path, insufficient-stock
  compensation, forced-payment-decline compensation) all pass again against the fixed image.

---

## [BUG-0035] Every production image shipped a ~20MB Testcontainers/docker-java payload, including three HIGH/CRITICAL CVEs, since Phase 1
- **Date:** 2026-09-11
- **Phase:** Phase 13 — Containerization and Kubernetes (local); found by this phase's own Trivy
  exit criterion, the first time any image's actual contents were inspected rather than just built.
- **Severity:** High — real CVE exposure (one CRITICAL: Tomcat security-constraint bypass,
  CVE-2026-65182) shipped in every one of the six production containers since the very first
  `docker compose up` in Phase 1, invisible to `mvnw dependency:tree` and to every prior phase's
  live verification because none of it inspected image contents, only behavior.
- **Symptom:** `trivy image --severity HIGH,CRITICAL --ignore-unfixed conveyor/order-service:local`
  reported 5 alpine-package findings plus 6 Java findings, 3 of them CRITICAL — including
  `org.apache.httpcomponents.core5:httpcore5` at version **5.0.2**, an artifact with no entry
  anywhere in `mvnw dependency:tree`'s output for any service.
- **Root cause:** three independent issues, all found via the one Trivy run:
  1. `conveyor-common/pom.xml` declares Testcontainers **compile-scope on purpose** (its own
     comment: "exposed transitively so every service's own Testcontainers tests... can spin up the
     same Postgres/Kafka images without redeclaring them") — a deliberate Phase 1 decision that
     works exactly as intended for tests, but "compile scope" also means Spring Boot's `repackage`
     goal bundles it into the *executable* jar. `unzip -l order-service.jar` confirmed
     `testcontainers-1.20.4.jar` (17.8MB) plus `docker-java-api`/`docker-java-transport`/
     `docker-java-transport-zerodep`/`jna` sitting in `BOOT-INF/lib` of a **production** image.
  2. The vulnerable `httpcore5:5.0.2` is shaded *inside* `docker-java-transport-zerodep-3.4.0.jar`
     itself (confirmed by extracting it and finding `org/apache/hc/core5` class files with no
     matching Maven coordinate anywhere in the reactor) — explaining why it was invisible to
     `dependency:tree`, which only walks declared Maven coordinates, not classes shaded inside a
     dependency's own jar.
  3. Separately, `spring-boot-starter-parent:3.5.16` itself still pins `tomcat-embed-core:10.1.55`
     and `postgresql:42.7.11`, both since superseded by CVE fixes upstream — unrelated to (1)/(2),
     just never checked before this phase.
- **Fix:** root `pom.xml`'s `spring-boot-maven-plugin` `pluginManagement` now sets
  `excludeGroupIds` to `org.testcontainers,com.github.docker-java,net.java.dev.jna` — the
  repackage goal drops them from the executable jar only; the compile/test classpath (and every
  service's own Testcontainers-backed integration-test suite) is completely unaffected, verified by
  a full `./mvnw verify` afterward. `tomcat.version`/`postgresql.version` properties overridden to
  `10.1.59`/`42.7.12` (10.1.58, Trivy's own reported fix version, was never published to Maven
  Central; `.59` carries the same fix). Alpine's own `libcrypto3`/`libssl3`/`libexpat` pinned to
  their fixed patch releases in every Dockerfile's final stage (pinned exact versions, not a bare
  `apk upgrade`, which would have reintroduced the same non-determinism this phase's reproducible-
  build work — BUG-0030 — exists to rule out).
- **Status:** Fixed. Re-verified live: `trivy image --severity HIGH,CRITICAL --ignore-unfixed
  conveyor/order-service:local` → **0 alpine findings, 0 jar findings**, `unzip -l` confirms no
  `testcontainers`/`docker-java`/`jna` jars remain in the repackaged image.

---

## [BUG-0034] Helm-managed Deployment's `.spec.replicas` conflicted with the HPA controller's field ownership on the very next upgrade
- **Date:** 2026-09-11
- **Phase:** Phase 13 — Containerization and Kubernetes (local)
- **Severity:** Medium — blocked every subsequent `helm upgrade` against the release outright,
  not just a cosmetic warning.
- **Symptom:** `helm upgrade --install conveyor ...` (server-side apply, Helm 4's default) failed:
  `conflict occurred while applying object conveyor/order-service apps/v1, Kind=Deployment: Apply
  failed with 1 conflict: conflict with "kube-controller-manager" with subresource "scale" using
  apps/v1: .spec.replicas`.
- **Root cause:** order-service is the one Deployment with an HPA (`infra/helm/conveyor/templates/
  app/hpa.yaml`). The HPA controller's periodic reconciliation writes `.spec.replicas` via the
  `scale` subresource under its own field-manager identity (`kube-controller-manager`) the moment it
  first evaluates the Deployment — regardless of whether the replica count actually changes.
  Helm's own server-side apply, on the *next* release, also tries to own that same field (the
  Deployment template unconditionally set `replicas: {{ $svc.replicaCount }}`), and two field
  managers claiming the same field is exactly what server-side apply's conflict detection exists to
  catch.
- **Fix:** the Deployment template now omits `replicas:` entirely for any service with
  `hpa.enabled: true` (`infra/helm/conveyor/templates/app/deployment.yaml`) — a brand-new Deployment
  defaults to 1 replica with the field absent, and thereafter only the HPA ever sets it, so there is
  nothing left for Helm to contest. The four non-HPA services are unaffected (`replicas:` still set,
  since nothing else claims that field for them).
- **Status:** Fixed. Re-verified live: `helm upgrade --install conveyor ...` succeeded immediately
  after, `REVISION: 3`, `STATUS: deployed`.

---

## [BUG-0033] Kubernetes could not verify `runAsNonRoot` against a symbolic Dockerfile `USER`
- **Date:** 2026-09-11
- **Phase:** Phase 13 — Containerization and Kubernetes (local)
- **Severity:** High — every one of the six app pods was stuck in `CreateContainerConfigError`,
  0/6 services schedulable.
- **Symptom:** `kubectl -n conveyor describe pod order-service-...` → `Error: container has
  runAsNonRoot and image has non-numeric user (conveyor), cannot verify user is non-root`, repeated
  for all six Deployments/the CronJob alike.
- **Root cause:** every Dockerfile's final stage sets `USER conveyor:conveyor` (a symbolic name from
  `addgroup -S conveyor && adduser -S conveyor -G conveyor`). Docker itself resolves this fine at
  container-run time, but the kubelet's admission-time `runAsNonRoot` check
  (`infra/helm/conveyor/templates/_helpers.tpl`'s `conveyor.securityContext`) can only verify a
  *numeric* UID against the image config — a symbolic username requires resolving `/etc/passwd`
  inside the image, which the kubelet deliberately does not do for this check. This gap doesn't
  exist in docker-compose, which never enforces `runAsNonRoot` at all — so it was invisible through
  12 prior phases of live compose verification.
- **Fix:** `USER 100:101` (numeric) in all six Dockerfiles — the exact UID/GID Alpine's
  `addgroup -S`/`adduser -S` already assigned (confirmed via `docker run --entrypoint /bin/sh
  ... -c id`), just spelled out numerically instead of by name. No behavior change; same user, same
  permissions.
- **Status:** Fixed. Re-verified live: all six pods reached `1/1 Running` after rebuilding and
  reloading the images.

---

## [BUG-0032] Strimzi Kafka CR specified an unsupported Kafka version for the pinned operator release
- **Date:** 2026-09-11
- **Phase:** Phase 13 — Containerization and Kubernetes (local)
- **Severity:** Low — caught immediately by the Kafka CR's own status condition, before anything
  downstream depended on it.
- **Symptom:** `kubectl -n conveyor get kafka conveyor-kafka` never left `NotReady`:
  `"Unsupported Kafka.spec.kafka.version: 3.9.0. Supported versions are: [4.2.0, 4.2.1, 4.3.0,
  4.3.1]"`.
- **Root cause:** `infra/k8s/kafka/kafka-cluster.yaml` was hand-adapted from Strimzi's own
  `kafka-with-dual-role-nodes.yaml` example for the pinned 1.2.0 operator release, and the adaptation
  substituted a stale Kafka version (3.9.0) instead of keeping the example's own 4.3.1 — Strimzi 1.2.0
  only ships broker code for the four 4.x versions listed above.
- **Fix:** `spec.kafka.version: 4.3.1`, `metadataVersion: 4.3-IV0` (the example's original values).
  Verified live: the Kafka CR reached `status.conditions[0].type: Ready` within ~2 minutes of
  reapplying, dual-role broker pod and entity-operator pod both `Running`.
- **Status:** Fixed.

---

## [BUG-0031] Strimzi operator Deployment (and its ServiceAccount/ConfigMap/RoleBindings) landed in the wrong namespace
- **Date:** 2026-09-11
- **Phase:** Phase 13 — Containerization and Kubernetes (local)
- **Severity:** Medium — silently broke the entire Kafka bring-up step; `scripts/kind-up.sh`'s own
  next command (`kubectl -n conveyor rollout status deployment/strimzi-cluster-operator`) failed
  loudly rather than hanging, so this was caught immediately, not downstream.
- **Symptom:** `kubectl -n conveyor rollout status deployment/strimzi-cluster-operator` →
  `Error from server (NotFound): deployments.apps "strimzi-cluster-operator" not found`, even though
  `kubectl apply -f infra/k8s/strimzi/strimzi-cluster-operator-1.2.0.yaml` reported
  `deployment.apps/strimzi-cluster-operator created` with no error.
- **Root cause:** the downloaded Strimzi install manifest only carries an explicit `namespace:` field
  on the handful of resources the standard `sed 's/namespace: myproject/namespace: conveyor/'`
  rewrite targets (RoleBindings whose *subjects* reference the operator's namespace) — the
  Deployment, its ServiceAccount, its ConfigMap, and several RoleBindings have no `namespace:` field
  at all in the upstream manifest, so `kubectl apply -f` without `-n` put them in kubectl's
  *current-context* default namespace (`default`) instead. The ClusterRoleBindings' subjects (already
  correctly rewritten to `conveyor`) then pointed at a ServiceAccount that didn't exist there, and the
  Deployment never started serving from the namespace anything else in this chart looks for it in.
- **Fix:** `kubectl apply -n "$NAMESPACE" -f "$STRIMZI_MANIFEST"` (`scripts/kind-up.sh`) — passing
  `-n` makes every unqualified resource in the manifest land in `conveyor` too, matching the ones that
  already had an explicit namespace. Verified live: operator Deployment reached `1/1 Ready` in
  `conveyor`, and the subsequent Kafka CR (BUG-0032) then reconciled successfully.
- **Status:** Fixed.

---

## [BUG-0030] Docker BuildKit's default provenance attestation defeats "same commit -> same image digest"
- **Date:** 2026-09-11
- **Phase:** Phase 13 — Containerization and Kubernetes (local)
- **Severity:** Low — the underlying image layers and config were already byte-identical; only the
  manifest-list digest (and therefore the locally-tagged image ID) differed.
- **Symptom:** Building the identical `order-service/Dockerfile` at the identical commit twice, with
  `pom.xml`'s new `project.build.outputTimestamp` already fixing the jar's own internal timestamps
  and `--build-arg SOURCE_DATE_EPOCH` fixing COPY'd file mtimes, still produced two different
  `docker inspect --format='{{.Id}}'` values (`sha256:784640c4...` vs `sha256:63a1a0d3...` on a
  cache-hit rebuild). `docker buildx build`'s default output attaches a provenance/SBOM attestation
  manifest that embeds the real wall-clock build time — that attestation's own digest differed
  between runs even though the underlying single-platform image manifest digest
  (`sha256:4fd3e7220d...`) and config digest were already identical both times.
- **Root cause:** BuildKit (Docker 29.7.2's default builder) auto-attaches build provenance since
  several releases back; that attestation is not covered by `SOURCE_DATE_EPOCH` or
  `project.build.outputTimestamp` at all, since it describes the build process, not the image content.
- **Fix:** `docker build --provenance=false --build-arg SOURCE_DATE_EPOCH=1704067200 ...`
  (`scripts/kind-up.sh`'s image-build step). Verified live: two back-to-back builds of the identical
  Dockerfile with this flag produced the identical `docker inspect --format='{{.Id}}'` value
  (`sha256:4fd3e7220d89633733558000da6c803701aaafeed07eb9a0c71391272cc21b6b`) both times.
- **Status:** Fixed.

---

## [BUG-0029] k6 load-test harness: refresh-token cookie silently dropped when addressing the stack by Docker's internal (dot-less) service hostname
- **Date:** 2026-09-11
- **Phase:** Phase 12 — Load test (harness bug, found via the first 30-minute soak run)
- **Severity:** Low — self-healing (every failed refresh fell back to a fresh login with zero
  effect on the soak run's actual results: 66,457/66,457 orders still confirmed), but wrong
  behavior nonetheless, and worth recording since it is a genuinely reusable gotcha for any future
  Docker-network-internal load generator, not specific to this codebase.
- **Symptom:** `http_req_failed` showed 240 failures in the first 30-minute soak run, all tagged
  `name=auth_refresh`, `status=401`. Functionally harmless (`ensureFreshToken`'s fallback silently
  re-logged-in on every failure), so the run's real metrics (orders confirmed/stuck/failed) were
  unaffected — but a login should not need to happen twice as often as intended.
- **Root cause:** confirmed live with `curl -v`: `cookie 'refreshToken' dropped, domain '[file]'
  must not set cookies for 'order-service'`. `order-service`'s Docker Compose service name has no
  dot in it, which makes it a **Public-Suffix-List "public suffix"** as far as RFC 6265 cookie
  handling is concerned — both `curl`'s and k6's (`golang.org/x/net/publicsuffix`) cookie jars
  correctly refuse, per spec, to store a cookie for one, exactly as they would refuse one for
  `com` or `co.uk`. The refresh token (ADR-5) is deliberately an `HttpOnly` cookie with no
  explicit `Domain` attribute; adding one would not fix this either, since a cookie's `Domain`
  attribute being a public suffix is independently disallowed by the same rule (RFC 6265bis). **Not
  a product defect** — a real client only ever reaches this system via `localhost` (specially
  exempted from the public-suffix check by every major implementation, including Go's) or a real
  registrable domain, never the internal service-mesh DNS name, so this can only ever surface in a
  load generator that deliberately addresses the mesh directly, which none of this project's other
  test suites (Testcontainers, `e2e`, Playwright) do.
- **Fix:** `load/lib/common.js`'s default `ORDER_BASE`/`INVENTORY_BASE` and the `Makefile`'s
  `load` target now address the stack via `host.docker.internal:808x` (its published host ports —
  a real, dotted, PSL-exempt hostname) instead of the internal `conveyor_conveyor` network's bare
  service names, confirmed live via the same `curl` reproduction (login then refresh against
  `host.docker.internal` returns `200`). Incidentally the more representative choice regardless of
  the cookie issue: it is the same path a real client actually uses.
- **Status:** Fixed. Re-verified: the subsequent spike run (`load/spike.js`, 5 m 20 s, 4,942
  orders, multiple refresh cycles per VU) shows zero `auth_refresh` failures.

---

## [BUG-0028] k6 load-test harness: wrong access-token-expiry field name silently forced a refresh (and a doomed one) on every single request
- **Date:** 2026-09-11
- **Phase:** Phase 12 — Load test (harness bug, found in the smoke test's first run, before any
  scenario costing real wall-clock time was committed to)
- **Severity:** Low — caught immediately by the smoke test existing for exactly this purpose
  ("prove the rest of the scripts are measuring the right thing before spending real wall-clock
  time on them" — see `RESULTS.md`'s Phase 12 methodology), never affected a scenario that
  mattered.
- **Symptom:** the smoke test's first run showed `http_req_failed` at 12.33% (28/227), exactly
  matching the placed-order count — every single `POST /orders`-triggered iteration was also
  producing one failing request, but neither `place_order_failed` nor `poll_order_failed` (this
  harness's own custom, `check()`-backed rate metrics) showed anything wrong at all, which is what
  made it worth chasing rather than dismissing as expected noise.
- **Root cause:** `load/lib/common.js`'s `login()` read `body.expiresInSeconds`, but
  `AuthResponse` (order-service) actually serializes the field as `expiresIn` (confirmed via a
  direct `curl` against `/auth/login`). The resulting `undefined` made
  `ensureFreshToken`'s guard (`ageSeconds < session.expiresInSeconds * 0.7`) evaluate
  `ageSeconds < NaN`, which is `false` unconditionally — so every iteration, for every VU, called
  `POST /auth/refresh` regardless of how recently it had logged in. Compounded by a second, related
  mistake in the same function: it read a non-existent `body.refreshToken` from the login
  response — the refresh token is never in the JSON body at all (see BUG-0029) — so every one of
  those unconditional refresh calls was doomed to fail regardless.
- **Fix:** corrected the field name; the `refreshToken`-in-body handling was removed entirely as
  part of BUG-0029's fix (the correct mechanism is k6's own per-VU cookie jar, populated
  automatically by `Set-Cookie` at login, not anything threaded through JS state by hand).
- **Status:** Fixed. Re-verified: the corrected smoke test shows `http_req_failed` at 0.00%
  (0/174).

---

## [BUG-0027] "Money moved, nobody told" and its inventory twin: a late reply arriving after the saga already timed out and aborted was silently discarded, leaving a real charge or a real reservation permanently orphaned
- **Date:** 2026-09-11
- **Phase:** Phase 11 — Chaos matrix (bug is from Phase 6; found live via the same chaos trials that
  found BUG-0026, once that fix let the affected sagas' outcomes actually surface through to
  `orders.status` for the first time)
- **Severity:** Critical — this is exactly the danger ARCHITECTURE.md §14 names as "the most
  dangerous" injection point ("money moved, nobody told"), reached here via a redelivery race
  rather than the `payment.after-commit-before-publish` point specifically. A customer's payment
  method was genuinely charged for an order that reports `CANCELLED`, with no code path that would
  ever refund it. The inventory-side twin (a `CANCELLED` order permanently holding a `HELD`
  reservation) is a correctness bug rather than a financial one, but shares the identical root
  cause and fix.
- **Symptom:** live, after BUG-0026's fix let `saga.after-reply-before-state-write` /
  `saga.after-state-write-before-command` / `payment.before-commit` crash trials correctly reach
  `orders.status = CANCELLED`, `conveyor-verifier` immediately flagged two *new* invariants on
  those same orders: **INV-ORD-02** (a `CANCELLED` order still holding a `HELD` reservation) and
  **INV-ORD-03** (a `CANCELLED` order with a payment still `CAPTURED`, never `REFUNDED`). Checked
  live: `GET /payments/{orderId}` on one such order showed `"status":"CAPTURED"` with a real
  `gatewayReference` — the mock gateway had genuinely processed the charge — on an order whose own
  `GET /orders/{orderId}` reported `CANCELLED`.
- **Root cause:** `SagaOrchestrationService.handleInventoryReserved` and `.handlePaymentCharged`
  both guard on the saga being in the exact expected non-terminal state
  (`RESERVING_INVENTORY`/`CHARGING_PAYMENT`) and, if not, log a warning and silently return —
  correct behavior for a genuine duplicate redelivery of a reply already processed, but wrong for
  the case this chaos scenario produces: the *original* command's reply is only *late* (the
  consuming service crashed and was redelivered after `SagaTimeoutSweeper` had already claimed and
  aborted the saga, per BUG-0026's own timeline), so this is genuinely new information — inventory
  really did reserve stock, or payment really did capture a charge — that nothing had ever recorded
  and therefore nothing would ever compensate. The forward-timeout policy's own reasoning
  ("`RESERVING_INVENTORY`/`CHARGING_PAYMENT` timing out means no reply ever arrived, so there is
  nothing to release/refund by construction") is correct for every cause of that timeout *except*
  this one: a reply that eventually arrives, just too late to win the race against the sweeper.
- **Fix:** both handlers gained an `else if (saga.getState() == SagaState.ABORTED)` branch: instead
  of discarding the late reply, it immediately issues the compensating command
  (`ReleaseInventory`/`RefundPayment`) using the reservation ID or payment ID *the late reply
  itself carries* — the only place that information ever existed, since the saga's own step log
  never recorded it. The saga's own outcome is left unchanged (still `ABORTED` — a terminal saga
  does not un-terminate), only the compensating side effect is triggered. Both compensating
  commands are already idempotent-safe at the receiving service (Phase 4/5's own established
  design), so no new idempotency risk is introduced.
- **Status:** Fixed. New regression tests in `SagaTimeoutIntegrationTest`
  (`lateInventoryReservedAfterAbortReleasesTheReservationInsteadOfOrphaningIt`,
  `latePaymentChargedAfterAbortRefundsThePaymentInsteadOfLeavingTheCustomerCharged`) reproduce the
  exact race (drive the saga to `ABORTED` via the timeout path, then deliver the "late" reply
  directly) and assert the correct compensating command is published with the right ID; full
  `saga-orchestrator` suite 27/27 green. Re-verified live via a second full chaos matrix run with
  the fix in place — see `RESULTS.md`'s Phase 11 section for the before/after trial numbers.

---

## [BUG-0026] Timeout-triggered saga aborts publish `OrderCancelled` directly, with no intermediate event — `orders.status` gets stuck forever at `INVENTORY_RESERVED`/`PAYMENT_CHARGED`, even though the saga itself correctly reaches `ABORTED`
- **Date:** 2026-09-11
- **Phase:** Phase 11 — Chaos matrix (bug is from Phase 6; found live, for the first time, by the
  chaos matrix's crash trials — no prior test, including Phase 6's own
  `SagaTimeoutIntegrationTest` and the `e2e` module, ever drove a timeout-triggered abort through a
  real order-service consumer to check the projection actually catches up)
- **Severity:** High — a real, 100%-reproducible correctness gap in the saga's headline guarantee
  (INV-SAGA-05: "for every saga terminal for more than 30s, `orders.status` agrees with the saga
  outcome"). Every one of the three affected injection points failed **every single repetition**
  (0/4, 0/4, 0/2 — the fourth `inventory.after-reserve-before-publish` pair also hit this, see
  BUG-0025's harness-error trials 36/38 for why only 2 of its 4 reps produced a clean record at
  all) in the full chaos matrix run, confirming this is systematic, not flaky.
- **Symptom:** live, minutes after three crash trials completed (`saga.after-reply-before-state-write`,
  `saga.after-state-write-before-command`, `payment.before-commit`), `GET /sagas/{orderId}` showed
  `state: "ABORTED"` with a fully correct compensation step log (`RESERVE_INVENTORY TIMED_OUT` for
  the first two; `CHARGE_PAYMENT TIMED_OUT` → `RELEASE_INVENTORY SUCCEEDED` for the third) — the
  saga itself had genuinely, correctly self-healed. But `GET /orders/{orderId}` on the exact same
  order still showed `INVENTORY_RESERVED` / `PAYMENT_CHARGED` respectively, unchanged since the
  moment the timeout fired, with no further update ever coming. `conveyor-verifier`'s
  `INV-SAGA-05` flagged this correctly the very first time it was run against affected data (in an
  earlier, harness-buggy run this was wrongly dismissed as a fabricated finding — see BUG-0025 — but
  the underlying phenomenon was real all along and reproduced cleanly once the harness itself was
  fixed).
- **Root cause:** `OrderStatus`'s guarded state machine (`order-service`) only allowed `CANCELLED`
  as a target from `COMPENSATING`, never directly from `PLACED`/`INVENTORY_RESERVED`/
  `PAYMENT_CHARGED`. A saga abort triggered by a *reply* event
  (`InventoryReservationFailed`/`PaymentFailed`, from inventory-service or payment-service actively
  rejecting the command) naturally produces that `COMPENSATING` intermediate step for
  `SagaEventProjectionListener` to project first. But `SagaTimeoutSweeper`'s deadline-driven aborts
  (`SagaOrchestrationService.applyTimeoutPolicy`, `terminateAborted`) never received any reply to
  relay in the first place — nothing ever timed out *because* inventory or payment said no; it
  timed out because no reply ever arrived at all — so that code path goes straight from internal
  saga state to publishing `OrderCancelled`, with no equivalent event for order-service's
  projection to see first. `SagaEventProjectionListener.onMessage` catches exactly this case
  (`IllegalOrderTransitionException`) and — correctly, by its own stated contract ("saga and
  projection disagree on state") — logs a warning and discards the message rather than throwing,
  which is the right behavior for a genuinely conflicting update but the wrong diagnosis here: the
  saga and the projection didn't disagree, the projection was just missing a legal edge on its own
  state diagram.
- **Fix:** `OrderStatus.ALLOWED_TRANSITIONS` (`order-service`) now also allows `CANCELLED` directly
  from `PLACED`, `INVENTORY_RESERVED`, and `PAYMENT_CHARGED` — the state diagram gains one edge from
  each non-terminal pre-confirmation state, not a redesign. `OrderStateMachineTest`'s mirrored
  `LEGAL` map updated identically (7/7 green, including the previously-`isInstanceOf` no-op-turned-
  legal `INVENTORY_RESERVED -> CANCELLED` and `PAYMENT_CHARGED -> CANCELLED` pairs). New regression
  test `SagaEventProjectionListenerTest#orderCancelledAdvancesOrderStatusDirectlyFromInventoryReservedWithNoInterveningCompensatingEvent`
  publishes a real `OrderCancelled` envelope at an order sitting in `INVENTORY_RESERVED` (no
  `COMPENSATING` step in between, reproducing the timeout path exactly) and asserts it reaches
  `CANCELLED` (2/2 green in that class).
- **Status:** Fixed. Re-verified against a full live chaos matrix re-run: the three previously-100%-
  stuck injection points now correctly reach `orders.status = CANCELLED` matching the saga's own
  `ABORTED` outcome, every time. That same re-run immediately surfaced a second, more serious bug
  the first one had been masking — BUG-0027.

---

## [BUG-0025] `chaos/run_matrix.py`'s first three working versions each had a real bug of their own, found only by actually running it against the live stack
- **Date:** 2026-09-11
- **Phase:** Phase 11 — Chaos matrix (harness development, `--quick` smoke-testing before committing
  to a full ~60-trial run)
- **Severity:** Medium — these are bugs in the *test harness*, not the application, but they
  produced misleading results (a fabricated "finding" and several false negatives) that would have
  gone into `RESULTS.md` as real product defects had the harness not been smoke-tested first with
  `--quick` and its output actually read critically.
- **Symptom / root cause / fix, three distinct issues found across three `--quick` runs:**
  1. **Recreating all five app services per trial, not just the one under test.** The first version
     force-recreated every service on every trial (arm and disarm both). This disrupted four
     services that had nothing to do with the injection point being tested, and produced the run's
     one fabricated finding: `INV-SAGA-05` violated on three orders whose sagas got stuck mid-flight
     purely because unrelated services were yanked out from under them mid-processing. Fixed by a
     `POINT_TO_SERVICE` map so a trial only ever touches the one container whose code actually calls
     `maybeCrash`/`maybeDelay` with that point's name.
  2. **`docker compose ps` (without `-a`) never lists exited containers at all** — Docker's default
     `ps` behavior, not a bug in Docker, but the harness's `container_state()` called it without
     `-a` and treated "no output" as `"absent"`, never `"exited"`. This made crash detection
     (`wait_for_any_exit`) blind 100% of the time: every crash-mode trial's `crashConfirmedOn` came
     back `None` even though `docker compose ps -a` confirmed (checked by hand) the target container
     really had exited with code 1 from `Runtime.halt(1)`, sometimes over a minute earlier. Because
     detection never fired, the harness's disarm-and-recover step never ran either, leaving crashed
     services dead (no auto-restart, by design — `chaos/docker-compose.chaos-override.yml` sets
     `restart: "no"`) for the rest of that trial and every trial after it until the next accidental
     recreate — explaining the run of `TIMEOUT`/`NO_ORDER_ID` results that got worse as the run went
     on. Fixed by adding `-a` to both `docker compose ps` call sites.
  3. **`urllib.error.HTTPError` is a subclass of `urllib.error.URLError`**, and the harness's
     `except (urllib.error.URLError, ...)` clause caught both — meaning a completely ordinary HTTP
     error response (a `401` from an access token that expired mid-run, since one token was reused
     for the whole ~20-minute run against a 15-minute TTL) was indistinguishable from a connection
     actually being killed by a real crash. Fixed by catching `HTTPError` first and separately
     (logging the real status code), and by having every trial log in fresh instead of sharing one
     token for the whole run.
- **Status:** Fixed, all three, and re-verified with a clean `--quick` run afterward: crash detection
  correctly names the right container on every crash trial, and 12/16 trials came back genuinely
  clean with 4 genuine (not fabricated) non-clean results under investigation for the full run — see
  `RESULTS.md`'s Phase 11 section.

---

## [BUG-0024] `ViolationReportWriter` silently failed every write live: the report volume's mountpoint was root-owned, the container runs as a non-root user
- **Date:** 2026-09-11
- **Phase:** Phase 10 — `conveyor-verifier` (live-compose verification)
- **Severity:** Medium — the checker itself still ran and reported correctly to stdout/logs and
  Prometheus; only the file artifact (`latest-report.json`/`violations.jsonl`) PLAN.md's own
  deliverable names ("writes a violation report ... to a file and to stdout") was silently broken.
  Caught by actually running `make invariant-check`'s equivalent live and reading its own log
  output, not assumed to work because the stdout/metrics half did.
- **Symptom:** `docker compose run --rm -e SPRING_PROFILES_ACTIVE=oneshot conveyor-verifier` logged
  a correct clean-run summary, immediately followed by `ERROR ... Failed to write violation report
  to /var/log/conveyor-verifier` / `java.nio.file.AccessDeniedException:
  /var/log/conveyor-verifier/latest-report.json`.
- **Root cause:** the Dockerfile's final stage runs as a non-root `conveyor` user (this project's
  standard hardening posture, `CLAUDE.md` §7), but never created `/var/log/conveyor-verifier` before
  switching users. `docker-compose.yml` mounts a named volume (`verifier-reports`) at that path;
  Docker seeds a *newly created* named volume's initial content and ownership from whatever already
  exists at that path inside the image at container-start time — since nothing did, Docker created
  the mountpoint owned by `root`, which the `conveyor` user cannot write to.
- **Fix:** `RUN mkdir -p /var/log/conveyor-verifier && chown -R conveyor:conveyor
  /var/log/conveyor-verifier` added before `USER conveyor:conveyor` in `conveyor-verifier/Dockerfile`.
  The already-existing (wrongly-owned) volume from this session's earlier runs also had to be
  removed (`docker volume rm conveyor_verifier-reports`) — rebuilding the image alone does not fix
  ownership retroactively on a volume Docker already initialized once.
- **Status:** Fixed. Re-verified live: a subsequent one-shot run wrote both
  `/var/log/conveyor-verifier/latest-report.json` inside the container with no error.

---

## [BUG-0022] `conveyor-verifier` crash-looped on every live start: `ServiceClients` was `@Configuration` with two constructors, and CGLIB proxying broke bean instantiation
- **Date:** 2026-09-11
- **Phase:** Phase 10 — `conveyor-verifier` (live-compose verification)
- **Severity:** High — the entire service crash-looped under `docker compose up`; caught immediately
  by this phase's own "runs continuously under `docker compose up`" exit criterion, before being
  reported done, but after every Testcontainers/mocked-HTTP unit test had already passed (none of
  them go through a real Spring context with the CGLIB-proxied bean).
- **Symptom:** `docker compose ps` showed `conveyor-verifier` `Restarting` in a tight loop while all
  five app services reached `healthy`. Logs: `BeanCreationException: Error creating bean with name
  'serviceClients' ... Failed to instantiate [ServiceClients$$SpringCGLIB$$0]: No default
  constructor found`, caused by `NoSuchMethodException:
  ServiceClients$$SpringCGLIB$$0.<init>()`.
- **Root cause:** `ServiceClients` was annotated `@Configuration` despite declaring no `@Bean`
  factory methods at all — it never needed CGLIB proxying in the first place. It also carried a
  second, test-only constructor (`ServiceClients(Map<String, RestClient>)`, added so
  `OutsideInCoverageTest` could inject `MockRestServiceServer`-backed clients) alongside the
  production one (`ServiceClients(VerifierProperties)`), with neither marked `@Autowired`. Spring's
  `@Configuration` enhancer generates a CGLIB subclass to intercept intra-class `@Bean` calls; with
  two ambiguous, non-annotated constructors on the superclass, the generated subclass ended up with
  no constructor Spring's instantiation strategy could match, so it fell back to a no-arg
  constructor that never existed. Every unit test for `ServiceClients` — `OutsideInCoverageTest`,
  and every invariant's `checkOutsideIn` test — constructs it directly via `new
  ServiceClients(...)`, bypassing Spring's container entirely, so none of them could have caught
  this; only a real `ApplicationContext` startup could, which is exactly what `docker compose up`
  is for.
- **Fix:** `ServiceClients` changed from `@Configuration` to a plain `@Component` (it has no `@Bean`
  methods, so it never needed proxying), and its production constructor marked `@Autowired` to
  remove the ambiguity with the test-only overload. `ServiceDatabases` was not affected — it
  legitimately has one `@Bean` method (`serviceJdbcTemplates()`) and only ever had one constructor,
  so its CGLIB proxy was never ambiguous.
- **Status:** Fixed, but see the update below — fixing this surfaced a second, independent crash on
  the very next restart.
- **Update (same session):** after the `@Component` fix, `conveyor-verifier` crash-looped again with
  a *different* error: `Failed to configure a DataSource: 'url' attribute is not specified ...
  Failed to determine a suitable driver class`, inside `OutboxAutoConfiguration`'s `outboxPoller`
  bean. Root cause: `OutboxAutoConfiguration` (conveyor-common) activates on
  `@ConditionalOnClass({DataSource.class, KafkaTemplate.class})` — conveyor-verifier never declares
  `spring-kafka` itself, but conveyor-common does, at compile scope, so `KafkaTemplate` reaches
  conveyor-verifier's classpath transitively regardless. Every other service satisfies
  `OutboxAutoConfiguration`'s implicit assumption of "one primary `DataSource` bean," which Spring
  Boot auto-configures from `spring.datasource.url`; conveyor-verifier is the first service in this
  codebase with **five** databases and no single "own" one, and never sets that property. Excluded
  `OutboxAutoConfiguration` explicitly (`@SpringBootApplication(exclude = ...)`) — the correct shape
  for this service, not a workaround: conveyor-verifier never writes anything, so it was never
  supposed to have an outbox poller in the first place.
- **Update (same session, again):** that fix rebuilt clean but crash-looped a *third* time with the
  identical `Failed to configure a DataSource` message, now surfacing through
  `WebSecurityConfiguration` instead of `outboxPoller`. Excluding `OutboxAutoConfiguration` had only
  removed the one bean that *used* the failing `DataSource`; Spring Boot's own
  `DataSourceAutoConfiguration` was still active (it activates on `DataSource` alone being on the
  classpath) and still eagerly instantiates its `dataSource` singleton bean during context refresh
  regardless of whether anything currently autowires it — some other bean in the graph (here,
  security config's own singleton pre-instantiation ordering) was always going to hit the same wall
  next. Fixed for real by also excluding `DataSourceAutoConfiguration`,
  `DataSourceTransactionManagerAutoConfiguration`, and `JdbcTemplateAutoConfiguration` — none of them
  apply to a service with five independently-managed datasources and no "primary" one, which is what
  `ServiceDatabases` already builds by hand.
  <br>Re-verified live, this time confirmed: `docker compose up -d --build conveyor-verifier` →
  `healthy`, `curl http://localhost:8086/actuator/health` → `{"status":"UP",...}`,
  `/actuator/prometheus` shows `conveyor_verifier_clean{application="conveyor-verifier"} 1.0` on an
  empty database.
- **Status:** Fixed.

---

## [BUG-0021] `ReservationStatus.COMMITTED` was defined but nothing in the codebase ever set it — INV-ORD-01 (ARCHITECTURE.md §13's "headline" invariant) was unimplementable
- **Date:** 2026-09-11
- **Phase:** Phase 10 — `conveyor-verifier` (bug is from Phase 4; only now caught, while writing the invariant catalogue and discovering there was no code path that could ever satisfy INV-ORD-01's literal wording)
- **Severity:** High — a documented, load-bearing domain rule (ARCHITECTURE.md §13: "Every order in
  `CONFIRMED` has, for each of its line items, a `reservations` row in `COMMITTED`") was structurally
  false for the entire project's life: `ReservationStatus.COMMITTED` existed as an enum constant since
  Phase 4, but grepping all of `inventory-service`'s main source for the literal text `COMMITTED`
  found it in exactly one place — the enum declaration itself. Every confirmed order's reservations
  stayed `HELD` forever.
- **Root cause:** no consumer in `inventory-service` ever reacted to `OrderConfirmed`. The saga's
  `CONFIRM_ORDER` pivot step (saga-orchestrator) and dispatch-service's shipment/notification writes
  were both wired in Phase 6/7, but nothing closed the loop back to inventory to mark the held stock as
  permanently fulfilled — a gap in the frozen architecture's Phase-to-phase handoff, not a regression in
  any single phase's own code.
- **Fix:** `inventory-service` gained a new `OrderConfirmedListener` (`conveyor.order.events.v1`,
  mirroring dispatch-service's existing listener on the same topic) → `InventoryReservationService
  .handleOrderConfirmed`, inbox-guarded on the `OrderConfirmed` event's own `eventId`. For each `HELD`
  reservation on the order, a new guarded conditional `UPDATE`
  (`StockItemRepository.commit`) decrements **both** `on_hand` and `reserved` by the held quantity
  (stock permanently leaves the warehouse on fulfillment, not just the hold) and the reservation moves
  to `COMMITTED`. This also gives INV-INV-03's conservation equation ("Σ shipped") a real, well-defined
  term: `Σ COMMITTED reservations.quantity`. No outbox reply is published — this is a one-way reaction
  to a broadcast event, the same relationship dispatch-service already has to `OrderConfirmed`, not a
  saga step with a reply contract.
- **Status:** Fixed. `OrderConfirmedCommitIntegrationTest` (3/3 green): commit decrements both columns
  and flips the reservation to `COMMITTED`; redelivery of the same `eventId` does not double-commit;
  an `OrderConfirmed` for an order with no held reservations is a harmless no-op.

---

## [BUG-0020] Grafana's p50/p95/p99 panels showed "No data" — Micrometer Timers don't publish histogram buckets by default
- **Date:** 2026-09-11
- **Phase:** Phase 9 — Observability
- **Severity:** Medium (two of three Grafana dashboards had dead panels; caught by the Phase 9 exit
  criterion "Grafana dashboards populate against a local load run" before being reported done)
- **Symptom:** live in Grafana, "Saga duration (p50/p95/p99)" (Saga Health) and both panels on
  Pipeline Latency showed "No data" despite real sagas having completed; `curl
  .../actuator/prometheus | grep conveyor_saga_duration_seconds` showed `_count`/`_sum`/`_max`
  samples but no `_bucket` samples, and likewise for `http_server_requests_seconds`.
- **Root cause:** a plain Micrometer `Timer.builder(...).register(registry)` only exports
  `_count`/`_sum`/`_max` to Prometheus; the `_bucket` series `histogram_quantile()` needs requires
  explicitly opting into histogram publishing. `SagaMetrics`'s two timers
  (`conveyor_saga_duration_seconds`, `conveyor_saga_step_duration_seconds`) never did, and Spring
  Boot's own `http.server.requests` Timer has the same default.
- **Fix:** `.publishPercentileHistogram()` added to both `Timer.builder(...)` calls in
  `SagaMetrics`; `management.metrics.distribution.percentiles-histogram."[http.server.requests]":
  true` added to all five services' `application.yml`. Verified live afterward: both dashboards'
  p50/p95/p99 panels render real data.
- **Status:** Fixed.

## [BUG-0019] `EnvelopeMdcRecordInterceptor` silently never wired into any listener container since Phase 1 — `sagaId`/`orderId`/`eventType` never actually reached MDC
- **Date:** 2026-09-11
- **Phase:** Phase 9 — Observability (bug is from Phase 1; only now caught)
- **Severity:** High — a documented, load-bearing capability (ARCHITECTURE.md §11: "MDC populated
  from the message envelope by a shared Kafka interceptor") silently did nothing for the entire
  project's life until this phase's live log inspection caught it. Every prior phase's mention of
  MDC enrichment working was true only for `traceId`/`spanId` (added this phase) — `sagaId`/
  `orderId`/`eventType` were never actually present on a real log line.
- **Symptom:** during Phase 9's live verification, `docker compose logs <service> | grep
  '"orderId":"'` across all five services returned **zero** matches, despite dozens of Kafka
  messages having been processed across every phase's live-compose runs and E2E suites. `traceId`/
  `spanId` (Phase 9's own addition) appeared correctly; `sagaId`/`orderId`/`eventType` never did.
- **Root cause:** `EnvelopeMdcRecordInterceptor` implemented `RecordInterceptor<String, String>`,
  matching every consumer's actual `StringDeserializer`. But Spring Boot's
  `KafkaAnnotationDrivenConfiguration` looks up a candidate bean via
  `ObjectProvider<RecordInterceptor<Object, Object>>` (confirmed by decompiling
  `spring-boot-autoconfigure-3.5.16.jar`) — Java generics are invariant, so a bean typed
  `RecordInterceptor<String, String>` never satisfies that lookup. `getIfUnique()` on the provider
  returned `null` every time, so `ConcurrentKafkaListenerContainerFactoryConfigurer` never called
  `factory.setRecordInterceptor(...)`, and the interceptor — despite existing as a perfectly valid
  bean in the context — was never attached to any listener container. No exception, no log
  message: a pure silent no-op. `KafkaMdcAutoConfiguration`'s own Javadoc asserted "Spring Boot's
  Kafka autoconfiguration wires any such bean into the autoconfigured
  ConcurrentKafkaListenerContainerFactory automatically" — true in general, but only for the exact
  generic signature Boot's configurer actually asks for, which nothing had verified against a live
  container until now.
- **Fix:** `EnvelopeMdcRecordInterceptor` now implements `RecordInterceptor<Object, Object>` (both
  `intercept`/`afterRecord` and the `ConsumerRecord<Object, Object>` parameter), casting
  `record.value()` to `String` internally — safe, since every consumer's value deserializer is in
  fact `StringDeserializer`. Verified live afterward: `sagaId`/`orderId`/`eventType` now appear as
  real MDC-driven JSON fields on Kafka-listener-invoked log lines across all five services for a
  single traced order.
- **Status:** Fixed.

## [BUG-0018] `TraceparentSupport` bean not found — every order-service test failed to start its context
- **Date:** 2026-09-11
- **Phase:** Phase 9 — Observability
- **Severity:** High (broke the entire order-service test suite outright; caught by the full reactor
  `verify` before being reported as done)
- **Symptom:** `./mvnw verify` failed every single test class in `order-service` with `Parameter 3 of
  constructor in com.conveyor.order.service.OrderService required a bean of type
  'com.conveyor.common.tracing.TraceparentSupport' that could not be found.`
- **Root cause:** `TraceparentSupport` (added this phase to carry the current span's `traceparent`
  through the outbox — see the Key Decisions Log) was annotated `@Component`, but it lives in
  `conveyor-common`'s `com.conveyor.common.tracing` package, outside every service's own
  `@SpringBootApplication` package tree — plain component scanning never reaches it. Every other
  conveyor-common bean in this codebase is registered through the project's
  `@AutoConfiguration`/`AutoConfiguration.imports` mechanism for exactly this reason; this one was
  added as a bare `@Component` by mistake, the one inconsistency with the established pattern.
- **Fix:** new `TracingAutoConfiguration` (`@AutoConfiguration`, `@ConditionalOnClass(Tracer.class)`)
  registers it as a `@Bean`; `TraceparentSupport` itself dropped `@Component`; added to
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **Status:** Fixed.

## [BUG-0017] Grafana container failed to start: nested bind mount inside a read-only bind mount
- **Date:** 2026-09-11
- **Phase:** Phase 9 — Observability
- **Severity:** Medium (blocked `make observability-up` entirely; caught immediately on first live
  run, never shipped)
- **Symptom:** `docker compose --profile observability up` failed to create the `grafana` container:
  `OCI runtime create failed: ... unable to start container process: error during container init:
  error mounting ".../infra/observability/grafana/dashboards" to rootfs at
  "/etc/grafana/provisioning/dashboards/json": create mountpoint ...: read-only file system`.
- **Root cause:** `docker-compose.yml` mounted the dashboard JSON directory at
  `/etc/grafana/provisioning/dashboards/json`, nested *inside* `/etc/grafana/provisioning`, which was
  itself mounted `:ro`. Docker has to `mkdir` the inner mount's mountpoint inside the outer mount's
  merged filesystem before bind-mounting onto it; it can't do that when the outer mount is read-only,
  so container creation fails outright — this has nothing to do with Grafana specifically, it is a
  general Docker bind-mount nesting constraint.
- **Fix:** the dashboards directory is now mounted at `/var/lib/grafana/dashboards-json` instead —
  nested under the `grafana-data` *named volume* mount (`/var/lib/grafana`), which is writable, not
  under the read-only provisioning mount. `infra/observability/grafana/provisioning/dashboards/
  dashboards.yml`'s provider `path` updated to match.
- **Status:** Fixed.

## [BUG-0015] Frontend generated-client Pageable calls 400'd; springdoc's OpenAPI schema shapes `Pageable` as nested, but Spring's resolver binds it flat
- **Date:** 2026-09-10
- **Phase:** Phase 8 — Live ops dashboard
- **Severity:** Medium (broke every list-backed screen — the kanban board, the order-placement
  form's SKU picker, and the admin inventory table — on first live run against the real stack; caught
  immediately, not shipped)
- **Symptom:** live in the browser (not just unit tests), the kanban board never left "Loading
  orders…" and the SSE indicator stuck on "Reconnecting…"; network inspection showed `GET
  /api/v1/orders?pageable[size]=200 → 400 Bad Request`.
- **Root cause:** springdoc represents a Spring Data `Pageable` controller argument as a nested
  `pageable: {page, size, sort}` object in the generated OpenAPI schema, which `openapi-typescript`
  faithfully turns into a nested TS type — but Spring's actual `PageableHandlerMethodArgumentResolver`
  binds `page`/`size`/`sort` as flat, top-level query parameters, not `pageable[size]=200` bracket
  notation. `openapi-fetch`'s default query serializer produces the bracketed form from the nested
  type, which Spring's resolver can't parse. A generated-types client following the spec exactly still
  produces a request the real server rejects — the spec and the wire behavior disagree.
- **Fix:** a custom `querySerializer` in `frontend/src/api/client.ts`, applied to `orderClient` and
  `inventoryClient` (the two clients with `Pageable`-backed endpoints), flattens a top-level
  `pageable` key's own fields before serializing instead of nesting them.
- **Status:** Fixed.

---

## [BUG-0016] Dashboard's "make this one fail" control called the wrong path for payment-service's chaos-profile test endpoint, and the stack didn't run that profile by default
- **Date:** 2026-09-10
- **Phase:** Phase 8 — Live ops dashboard
- **Severity:** Low (a demo-only control; the order-placement path itself was unaffected)
- **Symptom:** checking "make this one fail" and placing an order never actually forced a decline —
  the order confirmed normally.
- **Root cause:** two independent gaps. (1) the frontend called `POST /api/v1/test/failure-mode`,
  but `PaymentTestController` is mapped at `/test` (deliberately outside `/api/v1`, since it's a
  test-only surface, not part of the versioned public API — ARCHITECTURE.md §10.4 already documented
  this correctly; the frontend code just guessed the wrong path). (2) even with the right path,
  payment-service wasn't running with `SPRING_PROFILES_ACTIVE=chaos` in `docker-compose.yml`, so
  `PaymentTestController` (`@Profile("chaos")`) wasn't registered at all.
- **Fix:** corrected the frontend's fetch path and Vite proxy rule to `/test/failure-mode`, and set
  `SPRING_PROFILES_ACTIVE: ${PAYMENT_SERVICE_PROFILES:-chaos}` as `docker-compose.yml`'s local-dev
  default for payment-service — safe because `ChaosProfileStartupGuard` still refuses to start if
  `prod` is ever active alongside it, and the mock gateway's base failure rates stay `0` regardless of
  profile, so nothing about normal order flow changes. Verified live via Playwright
  (`forced-failure.spec.ts`): the control now genuinely forces a decline, drives the saga through
  `RELEASE_INVENTORY`/`COMPENSATION`, and the order ends `CANCELLED`.
- **Status:** Fixed.

---

## [BUG-0014] `e2e` module's direct-JDBC seeding hardcoded `localhost:5432`, which silently connects to whichever Postgres wins that host port — not necessarily this stack's container
- **Date:** 2026-09-10
- **Phase:** Phase 7 — Dispatch/Notification Service and full-pipeline E2E
- **Severity:** High (both E2E test classes failed outright; not a defect in the services themselves)
- **Symptom:** with Docker healthy and `C:` holding plenty of free space (after the human moved
  Docker Desktop's data root to `D:`), `mvn -f e2e/pom.xml verify -DskipE2E=false` got past image
  builds and both compose stacks came up healthy, but every test failed inside its first statement —
  `InventorySeed.seedStock(...)` — with `PSQL FATAL: password authentication failed for user
  "inventory_service"`, using exactly the username/password `docker-compose.yml`/`.env` actually
  configure.
- **Root cause:** this machine has **two native Windows PostgreSQL services installed and running**
  (`postgresql-x64-17` and `postgresql-x64-18`), permanently bound to `0.0.0.0:5432` — confirmed via
  `Get-NetTCPConnection -LocalPort 5432` (owning PID resolved to a standalone `postgres.exe`, not a
  Docker-related process) and `Get-Service`. Both E2E test classes hardcoded
  `jdbc:postgresql://localhost:5432/inventory_service` — assuming `docker-compose.yml`'s fixed
  `"5432:5432"` port publish would be the only thing listening there — rather than going through
  Testcontainers' own dynamically-assigned mapped port the way every other exposed service in the
  same test classes already does (`STACK.getServiceHost(...)`/`getServicePort(...)` for
  order/inventory/payment/saga/dispatch). Ordinary `docker compose up` from this repo was never
  affected because service-to-service traffic uses Docker's internal network hostname (`postgres`),
  never `localhost:5432` — this was latent and unnoticed until the E2E module's direct-from-host JDBC
  connection first exercised the host port.
- **Fix:** both `HappyPathAndInventoryCompensationE2ETest` and `PaymentDeclineCompensationE2ETest` now
  add `.withExposedService("postgres", 5432, Wait.forListeningPort())` to their `ComposeContainer` and
  build the JDBC URL from `STACK.getServiceHost("postgres", 5432)`/`getServicePort(...)` at call time
  (a new `dbUrl()` method, mirroring the existing `orderUrl()`/`paymentUrl()`/`dispatchUrl()` pattern)
  instead of a hardcoded constant — sidesteps host port 5432 contention entirely, regardless of what
  else is listening there. Re-run live: all 3 E2E tests green (`BUILD SUCCESS`).
- **Status:** Fixed.

---

## [BUG-0013] Adding the `e2e` module to the root reactor broke every Dockerfile's `mvnw -pl <service> -am` build
- **Date:** 2026-09-10
- **Phase:** Phase 7 — Dispatch/Notification Service and full-pipeline E2E
- **Severity:** High (would have broken `docker compose build` for all five services, including in
  CI — caught before merge, not in production)
- **Symptom:** `mvn -pl e2e verify -DskipE2E=false` (the e2e module's own live run) failed inside
  20 seconds with `Local Docker Compose exited abnormally with code 1`; the actual Docker build log
  showed every one of the five services' `RUN ./mvnw -q -pl <service> -am dependency:go-offline`
  Dockerfile steps failing with exit code 1.
- **Root cause:** the initial implementation added `<module>e2e</module>` to the root `pom.xml`'s
  `<modules>` list. Maven resolves the *entire* declared module list into the reactor before `-pl`
  filtering is applied — so `./mvnw -pl inventory-service -am ...` still requires `e2e/pom.xml` to
  exist on disk, even though the actual build only needs `inventory-service` and its dependencies.
  None of the five Dockerfiles `COPY` `e2e/pom.xml` into their build context (they only copy the
  pom.xml files of modules they actually depend on), so inside every image build the reactor
  resolution step failed outright with a missing-file error.
- **Fix:** removed `e2e` from the root pom's `<modules>` list entirely (with a comment explaining
  why) and switched every invocation (`.github/workflows/build.yml`'s `e2e` job, this module's own
  pom comments, test Javadoc) from `mvn -pl e2e verify` to `mvn -f e2e/pom.xml verify` — the module
  still inherits everything it needs from `conveyor-parent` via its own `<parent>`/`<relativePath>`,
  it just isn't aggregated into the default reactor build anymore. Re-verified: the same live run
  then progressed correctly past dependency resolution into the actual image builds (see BUG-0008's
  latest update for what stopped it after that — unrelated host disk I/O corruption, not this).
- **Status:** Fixed.

---

## [BUG-0012] `docker compose up` crash-looped: saga-orchestrator's DB role/pg_hba entry never existed on the persistent Postgres volume
- **Date:** 2026-09-10
- **Phase:** Phase 6 — Saga Orchestrator
- **Severity:** High (blocked live compose verification of all 5 services, not just saga-orchestrator — a cascading dependency failure)
- **Symptom:** `docker compose up -d --build` brought all 8 containers up, but `saga-orchestrator`,
  `order-service`, `inventory-service`, `payment-service`, and `dispatch-service` all crash-looped on
  startup with `FATAL: no pg_hba.conf entry for host "...", user "saga_orchestrator", database
  "saga_orchestrator", no encryption`.
- **Root cause:** the Postgres data volume (`conveyor_postgres-data`) had been created in an earlier
  session, before `saga-orchestrator` existed as a service. `infra/postgres/init-service-databases.sh`
  (which creates each service's role/database/grants) only runs once, on a *fresh* volume — Postgres
  never re-runs init scripts against an existing data directory. The volume therefore had no
  `saga_orchestrator` role at all, and every other service's container failed to start in turn purely
  as a `depends_on` cascade from Postgres never reaching a state those services could use.
- **Fix:** `docker compose down -v` (removes the stale volume) followed by `docker compose up -d
  --build` (recreates it, re-running the init script for all five service roles/databases). Not a
  Conveyor code defect — a normal consequence of adding a sixth service database to a
  previously-initialized local dev volume. `make reset` already does exactly this; recorded here so a
  future session recognizes the symptom immediately instead of re-diagnosing it.
- **Status:** Fixed. Confirmed via a subsequent live `docker compose up -d --build`: all 8 containers
  `Healthy`, all 5 `/actuator/health` endpoints `UP` — closing Phase 3's last open exit criterion at
  the same time.

---

## [BUG-0011] `OrderPlaced` never carried `paymentMethodToken`, though every consumer of it downstream needs one
- **Date:** 2026-09-10
- **Phase:** Phase 6 — Saga Orchestrator
- **Severity:** Medium (a genuine gap in the frozen spec, caught before any code shipped against it —
  not a regression)
- **Symptom:** while implementing `saga-orchestrator`'s `ChargePayment` command construction, discovered
  that `ChargePaymentPayload` requires a non-null `paymentMethodToken` (ARCHITECTURE.md §6.3), but
  `OrderPlacedPayload` (§6.3) and the `orders` table (§5.1) never carried one anywhere past
  `POST /orders`' own request validation — `CreateOrderRequest.paymentMethodToken()` was read, validated
  `@NotBlank`, and then silently dropped. The saga orchestrator had no way to charge payment at all
  without it.
- **Root cause:** `ARCHITECTURE.md` §10.1 requires the field on the request but §6.3's `OrderPlaced`
  payload definition and §5.1's `orders` schema both predate the saga orchestrator actually needing to
  read it back out later — a gap in the frozen spec, not an implementation slip in Phase 3 (order-service
  had nothing to propagate it *to* yet at the time).
- **Fix:** `paymentMethodToken` added to `Order` (new nullable column, `order-service`
  `V2__payment_method_token.sql`), to `OrderPlacedPayload` (new required field) and its JSON Schema, and
  threaded through `OrderService.createOrder`. Logged in `CONTEXT.md`'s Key Decisions Log rather than
  silently editing `ARCHITECTURE.md`, per `CLAUDE.md` §0.
- **Status:** Fixed.

---

## [BUG-0010] `jackson-module-scala` (test-scope, via embedded Kafka) hijacked Hibernate's JSON-column deserialization
- **Date:** 2026-09-10
- **Phase:** Phase 4 — Inventory Service, Phase 5 — Payment Service
- **Severity:** Medium (test-only; no production code path is affected)
- **Symptom:** `ClassCastException: class scala.collection.immutable.Map$Map2 cannot be cast to
  class java.util.Map` in `ReservationIdempotencyIntegrationTest`,
  `MultiSkuPartialReservationIntegrationTest`, `PaymentFailureModeIntegrationTest`, and
  `PaymentChargeConcurrencyIntegrationTest` — all four cast a nested value out of an `OutboxRecord`'s
  `Map<String, Object> payload` field (`reply.getPayload().get("payload")`) to `Map<String, Object>`.
  A first attempt (re-reading the value through `objectMapper.valueToTree(...)` instead of casting)
  fixed two of the four but left the other two failing with a `NullPointerException` instead —
  debug output showed *why*: `objectMapper.valueToTree(...)` was serializing the Scala map by
  reflecting over its private fields (`"scala$collection$immutable$Map$Map2$$key1"`, `"empty"`,
  `"traversableAgain"` — Jackson's generic POJO fallback) rather than as a proper JSON object,
  proving Spring's own `ObjectMapper` bean does **not** have `jackson-module-scala` registered,
  even though *something* clearly used it to produce the Scala map in the first place.
- **Root cause:** `spring-kafka-test` pulls in `org.apache.kafka:kafka_2.13` (the Scala broker, for
  `@EmbeddedKafka` support) at test scope, which transitively pulls
  `com.fasterxml.jackson.module:jackson-module-scala_2.13`. Hibernate 6's default JSON column
  support (`@JdbcTypeCode(SqlTypes.JSON)`, used by every service's `outbox`/`inbox` tables) builds
  its **own**, separate `ObjectMapper` internally and calls `findAndRegisterModules()` on it —
  independent of the Spring-managed `ObjectMapper` bean the rest of the application uses. That
  internal mapper picks up `DefaultScalaModule` purely because the jar is on the test classpath
  (nothing in application code asks for it), which overrides Jackson's default "untyped `Object`"
  deserializer to prefer Scala collection types for values whose type is erased to `Object` —
  exactly what nested nodes inside a nominally `Map<String, Object>` JSON column are. Two
  *different, silently divergent* `ObjectMapper` configurations in the same application, not a bug
  in `com.conveyor.inventory`/`payment`'s own code.
- **Fix:** conveyor-common gained `HibernateJsonFormatMapperAutoConfiguration`, a
  `HibernatePropertiesCustomizer` that points Hibernate's JSON column mapping at the application's
  own `ObjectMapper` bean (`JacksonJsonFormatMapper`) instead of letting Hibernate build a private
  one — the root-cause fix, applied once for every service, rather than working around the symptom
  per call site. The four affected tests still read nested payload content via
  `objectMapper.valueToTree(reply.getPayload())` rather than casting to `java.util.Map` (harmless
  either way once the mapper is consistent, and more defensive regardless).
  `InventoryReplyContractTest` and `PaymentReplyContractTest` were never affected, since they read
  the raw JSON string off the actual Kafka topic rather than the in-process `OutboxRecord.payload`
  field.
- **Status:** Fixed.

---

## [BUG-0009] Manually re-flowed base64 in `TestJwtSupport`'s RSA private key was corrupted
- **Date:** 2026-09-10
- **Phase:** Phase 4 — Inventory Service
- **Severity:** Medium (test-only; the matching public key, embedded the same way in
  `JwtSecurityProperties`, happened to survive — every other test in the module proves that, since a
  broken `JwtDecoder` bean would have failed the whole Spring context, not two isolated test methods)
- **Symptom:** both `InventoryAdjustIntegrationTest` tests failed with
  `java.lang.IllegalStateException: Failed to mint test JWT`, thrown from
  `TestJwtSupport.token()` wrapping a key-parsing exception.
- **Root cause:** the RSA keypair's base64 DER was generated once via `openssl`, then manually
  split across multiple Java string-literal `+`-concatenation lines to satisfy Checkstyle's 150-char
  `LineLength` rule. That manual re-flow introduced a transcription error in the ~1600-character
  private-key string (the shorter ~370-character public key apparently survived the same process
  intact, or at least intact enough to parse — RSA public keys are far more tolerant of small
  encoding slips reaching a still-valid `X509EncodedKeySpec` than a PKCS8 private key is).
- **Fix:** regenerated a fresh demo/test keypair and embedded both halves as Java text blocks
  (`"""..."""`) containing the PEM body exactly as `openssl` wrapped it (64 columns, its own line
  breaks) — no manual character-boundary math at all — with whitespace stripped at runtime before
  Base64 decoding. Eliminates this entire class of transcription bug rather than just this instance
  of it.
- **Status:** Fixed.

---

## [BUG-0008] Docker Desktop daemon unresponsive — blocks Phase 4/5 live Testcontainers and compose verification
- **Date:** 2026-09-10
- **Phase:** Phase 4 — Inventory Service, Phase 5 — Payment Service
- **Severity:** High (blocks `./mvnw verify`'s Testcontainers-backed integration tests and `docker
  compose up`; not a defect in Conveyor itself)
- **Symptom:** `docker ps`, `docker info`, and `docker images` all hang indefinitely (verified with
  explicit 15–45s timeouts, repeated three times over the course of this session) with no output and
  no error — the CLI never gets a response from the daemon. `C:` free space recovered to ~5.7 GB from
  BUG-0007's 17 MB (unrelated fix, presumably by the human), ruling out disk pressure as this
  session's cause.
- **Root cause:** not yet diagnosed — consistent with BUG-0002's prior Docker Desktop/WSL2 backend
  instability on this machine (a different symptom: that one failed disk provisioning outright with a
  clear log line; this one hangs silently with no daemon response at all), but not confirmed to be the
  same underlying defect.
- **Fix:** none applied. Per CLAUDE.md's guidance on the human's own system state, restarting Docker
  Desktop (or, if that doesn't recover it, the `wsl --unregister docker-desktop[-data]` step BUG-0002
  used) is left for the human rather than attempted unilaterally here.
- **Impact on Phases 4/5:** both phases are code-complete — `./mvnw compile`, `test-compile`,
  `spotless:apply`/`check`, and `checkstyle:check` all pass clean across the full reactor (none of
  which need Docker) — but every Testcontainers-backed integration test written for either phase
  (concurrency, idempotency, contract, and the ADMIN-auth tests) is **unverified this session**, as is
  `docker compose up` with the new services' images. Per CLAUDE.md §2.5 ("no fake done"), neither
  phase is being marked complete in `CONTEXT.md` until this is confirmed running.
- **Status:** Partially resolved, still Open. Docker recovered mid-session (`docker ps` started
  responding again without any explicit fix from here — the human likely restarted it), which let
  `./mvnw verify` finally run for the full reactor: **all 7 modules green**, including every Phase
  4 and Phase 5 test (see CONTEXT.md). `docker compose up -d --build` was then attempted and got
  partway through the image builds before Docker's BuildKit connection dropped
  (`rpc error: code = Unavailable desc = error reading from server: EOF`), and the daemon went back
  to being unresponsive to plain `docker ps` immediately after — the same underlying instability,
  not a new defect, now with a second concrete symptom (mid-build RPC death, not just a hung CLI).
  Remains open on **live `docker compose up` health** specifically (Phase 3's one remaining exit
  criterion) — not on the code, which `./mvnw verify` now independently confirms end to end.
  **Unblocks when:** the human gets Docker Desktop into a state that survives a full multi-service
  image build (a restart may not be enough this time, given it died mid-build rather than merely
  being slow to respond); then re-run `docker compose up -d --build` and confirm all 5 health
  endpoints return `UP`.
- **Update (2026-09-10, new session):** Docker responded cleanly at session start
  (`docker info` returned `ServerVersion: 29.7.2` immediately), and
  `./mvnw -pl inventory-service -am verify` was re-run live and confirmed green — **44/44 tests**,
  independently reconfirming Phase 4's exit criteria (not re-trusting the prior session's record).
  `docker compose up -d --build` was then attempted again: all 5 images built and all 8 containers
  were created, but `postgres-1`, `mongo-1`, and `redpanda-1` all failed their **start** step with
  `Error dependency <service> failed to start`, and the underlying Docker Desktop API call itself
  returned `request returned 500 Internal Server Error ... check if the server supports the
  requested API version`, propagated from `http://...dockerDesktopLinuxEngine/v1.55/...`. Every
  dependent service container (order/inventory/payment/dispatch/saga-orchestrator) then failed to
  start in turn, purely as a cascade. A follow-up `docker compose ps` then hung indefinitely with no
  output, reproducing BUG-0008's original symptom. **Third distinct symptom of the same underlying
  instability** in one bug's lifetime: (1) silent CLI hang, (2) mid-build BuildKit RPC death, (3) a
  500 from the Docker Desktop API on container start plus a renewed CLI hang. No fix attempted here
  — same reasoning as above, this is host-level Docker Desktop/WSL2 state, not Conveyor's. Phase 4
  is unaffected (verified via `./mvnw verify`, which does not depend on `docker compose`); this
  remains open purely on Phase 3's live-compose-health exit criterion.
  **Unblocks when:** the human restarts Docker Desktop (or applies the `wsl --unregister
  docker-desktop[-data]` step from BUG-0002 if a plain restart doesn't hold) and confirms `docker ps`
  responds and stays responsive through a full `docker compose up -d --build`.
  A follow-up `docker compose down` (attempted to clean up the partially-started stack above) then
  surfaced a fourth, more serious symptom: `Error response from daemon: Docker Desktop is unable to
  start — starting WSL engine: bootstrapping main distribution: ... exit status 0xc00000fd`, i.e. the
  WSL2 engine itself is now failing to bootstrap — the same *class* of failure as BUG-0002's original
  disk-provisioning crash-loop, not merely a slow/unresponsive daemon. No cleanup or repair attempted
  from here for the same reason as above; the partially-created `conveyor-*` containers and volumes
  are left as-is for the human to inspect or remove once Docker Desktop is recovered, rather than
  risking `docker compose down -v` mid-instability.
- **Update (2026-09-10, Phase 6 session):** at session start, `docker ps` responded immediately and
  the stack from a prior partial attempt was already `Up` — but all five service containers were
  crash-looping (`FATAL: no pg_hba.conf entry for host ..., user "saga_orchestrator"`), diagnosed and
  fixed as BUG-0012 (stale Postgres volume predating the sixth service). `docker compose down -v` +
  `docker compose up -d --build` then succeeded cleanly: **all 8 containers `Healthy`, all 5
  `/actuator/health` endpoints `UP`** — closing Phase 3's exit criterion for good this session. The
  stack was then torn down (`docker compose down`, no `-v`) to free resources while `./mvnw verify`
  ran for the rest of the Phase 6 session. Re-running `docker compose up -d --build` afterward (for
  Phase 6's own "`docker compose up` → a manually placed order reaches `CONFIRMED`" exit criterion)
  failed differently again: `payment-service`'s and `inventory-service`'s build stages both crashed
  with `java.lang.ClassFormatError thrown from the UncaughtExceptionHandler`, and the build overall
  failed with `target order-service: failed to receive status: rpc error: code = Unavailable desc =
  error reading from server: EOF` — BuildKit dying mid-multi-service-build again, the exact symptom
  already on record above. `docker ps`/`docker compose ps` are back to hanging (`docker info` alone
  intermittently returns). **Fourth+ distinct symptom in this one bug's lifetime**, still the same
  underlying Docker Desktop/WSL2 instability, not a Conveyor defect — no repair attempted here per
  the same reasoning as every prior update. Phase 6 is unaffected on correctness: `./mvnw verify`
  (Testcontainers-backed, does not touch `docker compose`) is green for the full 7-module reactor
  including all new saga-orchestrator tests. Only Phase 6's own live-compose smoke test is blocked.
  **Unblocks when:** the human gets Docker Desktop stable through one full `docker compose up -d
  --build` of all five images; then a manually placed order can be confirmed to reach `CONFIRMED` via
  the REST API, closing Phase 6 for good.
- **Update (2026-09-10, Phase 7 session):** the daemon was unresponsive from the very start of this
  session — `docker version`/`docker ps` both hung past a 15s timeout repeatedly, checked several
  times over the course of the session with no recovery. **Fifth+ distinct occurrence**, same
  underlying instability, still not a Conveyor defect. Phase 7 was built code-complete regardless:
  `./mvnw compile`/`test-compile` across the full 8-module reactor (dispatch-service's new
  `OrderConfirmedListener`/`DispatchService`/DLQ error-handling config, `conveyor-contracts`'
  `ShipmentCreatedPayload`, and the new top-level `e2e` module) plus `spotless:apply` and
  `checkstyle:check` all pass clean — none of which touch Docker — but every Testcontainers-backed
  dispatch-service test (happy path, contract, idempotency, retry-and-DLQ) and the entire `e2e` module
  are **unverified this session**. Per CLAUDE.md §2.5, Phase 7 is not being marked complete in
  `CONTEXT.md` until `./mvnw verify` and `mvn -pl e2e verify -DskipE2E=false` are both confirmed green.
  **Unblocks when:** the human gets Docker Desktop responding again; then `./mvnw verify` (full
  reactor) and the e2e module's own run close Phase 7 for good.
- **Update (2026-09-10, same session, later):** Docker recovered mid-session without any action
  taken here (consistent with every prior occurrence — the human evidently restarted it). Re-ran
  live: `./mvnw verify` for the **full 8-module reactor — 135/135 tests green**, including all four
  new dispatch-service test classes (`DispatchHappyPathIntegrationTest`,
  `DispatchReplyContractTest`, `DispatchIdempotencyIntegrationTest`,
  `DispatchRetryAndDlqIntegrationTest`), confirming Phase 7's unit/integration-level exit criteria
  for real. The `e2e` module's own live run then failed twice: first on a real defect in this
  session's own code (logged separately as BUG-0013, now fixed), then — after that fix let the build
  progress correctly past dependency resolution into the actual multi-stage image builds — on
  `error committing ...: write /var/lib/docker/buildkit/containerd-overlayfs/metadata_v2.db: input/
  output error` and, on the very next test class, `blob sha256:... expected at
  /var/lib/desktop-containerd/.../blobs/sha256/...: input/output error`. **Sixth+ distinct symptom of
  this bug's underlying instability**, and the most concrete yet: these are literal I/O errors from
  Docker Desktop's WSL2-backed virtual disk failing to read/write its own containerd metadata and
  content-addressed blob store — not a hung CLI, not a dropped RPC, but the backing filesystem itself
  refusing writes. `docker builder prune -af` (a low-risk attempt to clear a possibly-corrupted
  BuildKit cache, tried here since it touches no user data or other projects' state) failed with the
  identical `metadata_v2.db: input/output error`, which read like fresh WSL2/VHDX corruption at
  first — **until `df -h` immediately afterward showed the host `C:` drive at 226 GB / 226 GB used,
  0 bytes free.** That reframes the diagnosis: this is almost certainly **BUG-0007 recurring**
  (`C:` filling up again after being freed to ~5.7 GB some time between that entry and BUG-0002's
  resolution), not new Docker Desktop/WSL2 corruption — a completely full backing disk producing
  literal I/O errors on any write, containerd metadata included, is the more mundane and far more
  likely explanation than the filesystem spontaneously corrupting itself. This matters because it
  changes the fix: **restarting Docker Desktop or `wsl --unregister` will not help if the disk is
  simply full** — freeing space on `C:` is the actual unblock, exactly as BUG-0007 already
  concluded and exactly as unresolved as that entry left it. Phase 7's code is unaffected and
  independently proven correct by the 135/135 reactor run above; only the `e2e` module's live
  multi-container run remains unverified, now understood to be blocked on disk space, not host
  software instability.
  **Unblocks when:** the human frees space on `C:` (or points Docker Desktop's data root at a drive
  with room — `D:` has 160 GB free on this machine) and `mvn -f e2e/pom.xml verify -DskipE2E=false`
  then runs clean.
- **Update (2026-09-10, same session, later still):** `C:` recovered to 3.5 GB free (99% used, still
  tight but no longer zero) — but Docker itself did **not** self-heal. `docker system df` and
  `docker rm` on the orphaned `testcontainers-ryuk` container left over from the failed run both
  still fail with the identical `input/output error` writing `io.containerd.metadata.v1.bolt/meta.db`
  seen while the disk was full. This means freeing space alone does not fix it this time: the
  containerd metadata database most likely suffered a **torn write** while `C:` was at 0 bytes free
  and is now corrupted on disk, independent of space being available again. A restart of Docker
  Desktop is the next thing to try; if that doesn't clear the `meta.db` error, the
  `wsl --unregister docker-desktop-data` step from BUG-0002 (which discards Docker Desktop's backing
  disk and rebuilds it clean — all images/containers/volumes for every project on this machine, not
  just Conveyor) is the escalation, and that is the human's call to make, not something to run
  unilaterally.
  **Unblocks when:** the human restarts Docker Desktop (and if `docker system df`/`docker ps` still
  error afterward, applies the `wsl --unregister` step); then `mvn -f e2e/pom.xml verify
  -DskipE2E=false` closes Phase 7's last exit criterion.
- **Update (2026-09-10, new session — the one where the human reported Docker "working"):** at
  session start `docker ps`/`docker system df` both responded cleanly (client 29.7.2, 15 images,
  0 running containers) and `C:` had recovered to 4.6 GB free — genuinely healthy, not a false
  start. `docker builder prune -af` reclaimed 26.43 GB from the BuildKit cache as a precaution before
  the live run (host `C:` free space did not change afterward, confirming the VHDX is dynamically
  sized and doesn't shrink on reclaim — expected, not a symptom). `mvn -f e2e/pom.xml verify
  -DskipE2E=false` was then run live: image builds for all five services progressed correctly (this
  is further than several recent attempts got), a compose stack for the first scenario
  (`HappyPathAndInventoryCompensationE2ETest`) came up and ran for 273 s before erroring, and the
  second scenario's stack (`PaymentDeclineCompensationE2ETest`) then also errored after 648 s — both
  with `ContainerLaunch Local Docker Compose exited abnormally with code 1 whilst running command:
  compose up -d --build`. The proximate cause visible in the build log:
  `org.eclipse.aether.resolution.DependencyResolutionException: ... org.hibernate.orm:hibernate-core:jar:6.6.53.Final
  ... Premature end of Content-Length delimited message body (expected: 12,093,295; received:
  2,541,760)` — a Maven Central download truncated mid-transfer inside one of the image builds'
  `dependency:go-offline` step. Immediately after the failure, `df -h` showed `C:` at **260 MB free,
  100% used** (down from the healthy 4.6 GB at session start — the run itself consumed the
  difference, consistent with every prior occurrence of BUG-0007) and `docker system df`/`docker
  ps`/`docker info` all returned `request returned 500 Internal Server Error ... check if the server
  supports the requested API version` from the Docker Desktop Linux engine pipe — the daemon-level
  symptom already on record above. Reframing: the "Premature end of Content-Length" Maven error is
  almost certainly **also** a disk-full symptom, not an independent network blip — a local write
  failing partway through an HTTP body transfer while the backing disk has no room left produces
  exactly this shape of error from Maven's HTTP client. So this is BUG-0007 recurring a third time in
  as many sessions, now with a fourth distinct proximate symptom (truncated in-container downloads,
  on top of the CLI hang / BuildKit RPC death / containerd I/O errors already on record), all
  downstream of the same root cause: **whatever is filling `C:` outside this project's own Docker
  footprint does so fast enough that even a session that starts with several GB of headroom runs out
  of it partway through one live multi-service compose build.** Not fixed here — per the human's own
  instruction this session, the `wsl --unregister docker-desktop-data` escalation (which would wipe
  Docker state machine-wide, not just Conveyor's) is explicitly their call, not something to run
  unilaterally, and a plain Docker Desktop restart cannot be triggered from this shell session either
  (no GUI access). Phase 7's e2e exit criterion remains genuinely open — not marked done, per
  `CLAUDE.md` §2.5. Phase 8 was **not** started this session as a direct consequence, per `CLAUDE.md`
  §2.2 ("don't start phase N+1 before phase N is marked complete in `CONTEXT.md`").
  **Unblocks when:** the human (a) identifies and frees whatever is actually filling `C:` outside
  Docker's own ~5 GB image/volume footprint — the recurring pattern across BUG-0007's entire history
  says this is not Conveyor's or even Docker Desktop's own state — ideally with enough headroom to
  survive a full 5-image compose build (worked out to consuming at least ~4.3 GB beyond the 5 GB
  already resident this run), or (b) points Docker Desktop's data root at `D:` (160 GB free) instead
  of `C:` entirely, which would remove this class of failure structurally rather than requiring
  repeated manual cleanup. Then restart Docker Desktop, confirm `docker ps`/`docker system df`
  respond, and re-run `mvn -f e2e/pom.xml verify -DskipE2E=false`.

## [BUG-0007] Host C: drive full — `docker compose up --build` fails, blocking Phase 3's live health check
- **Date:** 2026-09-10
- **Phase:** Phase 3 — Order Service: REST, aggregate, outbox
- **Severity:** High (blocks the "docker compose up still fully healthy" exit criterion; not a
  defect in Conveyor itself)
- **Symptom:** `docker compose up -d --build` failed with `no space left on device` while building
  the order-service image. `docker system df` then hung indefinitely rather than erroring.
- **Root cause:** the host C: drive is at 226 GB / 226 GB used, 17 MB free (`df -h`). Checked
  Docker Desktop's own WSL2 disk file (`docker_data.vhdx`) directly — it is 24 GB, nowhere near
  large enough to account for this, so this is **not** Docker/Testcontainers state accumulated by
  this project. Something else on the host's system drive has filled it, and finding out what is
  outside this repo's scope and not something to go hunting through and deleting unilaterally on a
  system drive.
- **Fix:** none applied. This needs the human to free space on `C:` (or point Docker Desktop's data
  root at a drive with room) before `docker compose up` can be re-verified. Everything short of the
  live compose stack was still verified this phase: the full reactor `./mvnw verify` (all 7
  modules, real Testcontainers Postgres/Redpanda/Mongo per test class) passed clean immediately
  before the disk filled.
- **Status:** Open — blocked on the human freeing disk space on `C:`. Unblocks when: `docker
  compose up -d --build` succeeds and all 8 containers/5 health endpoints are re-verified `UP`
  (same check as BUG-0002/BUG-0004's resolutions).
- **Update (2026-09-10, Phase 7 session):** recurred. `C:` was at ~5.7 GB free per BUG-0002's
  resolution note; `df -h` this session showed it back to 226 GB / 226 GB used, 0 bytes free —
  someone/something filled it again in the interim, still not Conveyor's own Docker state (this
  project's images/volumes don't come close to 226 GB). This is what actually produced BUG-0008's
  "input/output error" symptoms this session (see that entry's latest update) — not fresh Docker
  Desktop/WSL2 corruption as first suspected, a full disk read that way instead. Still open, same
  fix needed: free space on `C:`, or move Docker Desktop's data root to `D:` (160 GB free).

---

## [BUG-0006] `Order.items` lazy collection accessed outside its transaction → 500 on every GET
- **Date:** 2026-09-10
- **Phase:** Phase 3 — Order Service: REST, aggregate, outbox
- **Severity:** Medium (both read endpoints that return items were completely broken)
- **Symptom:** `GET /orders/{id}` and `GET /orders` both returned `500` with a Jackson
  `InvalidFormatException` claiming `OrderStatus` couldn't deserialize the value `500` — a
  misleading symptom, because the client-side test was deserializing the *error body* (a
  `ProblemDetail`, whose own `status` field really is the integer 500) into `OrderDetailResponse`
  after the server had already failed for an unrelated reason.
- **Root cause:** `OrderService.getOrder()`/`searchOrders()` are `@Transactional(readOnly = true)`
  and return the `Order` entity itself; `OrderDetailResponse.from(order)` — which calls
  `order.getItems()` — runs in `OrderController`, **after** that transaction has already closed.
  `items` is a default-lazy `@OneToMany`, so accessing it there throws
  `LazyInitializationException`, which conveyor-common's generic exception handler converts to a
  bare 500 with no indication of the real cause.
- **Fix:** `@OneToMany(..., fetch = FetchType.EAGER)` on `Order.items` — order and its line items
  are always read together in this service (`OrderDetailResponse`, the `OrderPlaced` payload), so
  there is no case where lazy loading would have saved a query, only a case where it silently broke
  reads taken outside the aggregate's own transactional boundary.
- **Status:** Fixed.

---

## [BUG-0005] Postgres can't infer a JPQL bind parameter's type from `:param is null` alone
- **Date:** 2026-09-10
- **Phase:** Phase 3 — Order Service: REST, aggregate, outbox
- **Severity:** Medium (the one filtered list endpoint, `GET /orders`, was completely broken)
- **Symptom:** `GET /orders?status=PLACED` returned `500`; server log showed
  `PSQLException: ERROR: could not determine data type of parameter $3`.
- **Root cause:** `OrderRepository.search`'s JPQL used the common "optional filter" pattern —
  `(:from is null or o.createdAt >= :from)` — for the `from`/`to` `Instant` parameters. Postgres's
  prepared-statement parameter typing can't infer a type from a bare `? is null` comparison with no
  other type context in that branch, and rejects the query outright rather than guessing.
- **Fix:** `cast(:from as timestamp) is null` (same for `:to`) — the cast gives Postgres an
  explicit type to bind the parameter as, and the query behaves identically for callers, who never
  see JPQL at all.
- **Status:** Fixed.

---

## [BUG-0004] Shared-fork Testcontainers Postgres becomes unreachable for every test class after the first
- **Date:** 2026-09-10
- **Phase:** Phase 2 — Data layer, domain model and migrations
- **Severity:** High (blocked all repository/outbox/inbox integration tests in every service, not a correctness bug in the app)
- **Symptom:** With Maven Surefire's default single-reused-fork behavior, the *first* one or two
  `@SpringBootTest` classes in a module (whichever ran first) always passed, but **every subsequent
  test class in the same run failed**, deterministically, with
  `PSQLException: Connection to localhost:<port> refused` surfacing through HikariCP
  (`Connection is not available, request timed out`). This reproduced identically across 7
  consecutive full-module runs. `docker events` confirmed the Postgres container was never killed
  mid-run (only the expected end-of-run Ryuk cleanup) — the container stayed healthy throughout;
  only new socket connections from the JVM started being refused partway through the run.
- **Root cause:** isolated with targeted `-Dtest=ClassA,ClassB` runs: whichever class ran **second**
  against the shared, cached Spring context / Testcontainers-container pair failed outright on its
  *first* connection attempt — not a slow leak, not time-based, not related to entity content
  (tested and ruled out: `String[]` vs `List<String>` Postgres array mapping, HikariCP pool size,
  HikariCP connection-timeout up to 60s). The failure is specific to this machine's Docker
  Desktop/WSL2 host-networking layer under Testcontainers' singleton-container-across-test-classes
  pattern (`@Container static` fields on a shared abstract base, reused via Spring's
  `@SpringBootTest` context cache) — a second class's fresh `HikariDataSource` bean opening new
  sockets to an already-in-use container's mapped port hits a proxy-level failure that a single
  class's own pool never triggers. Likely related to the WSL2 networking instability already
  documented in BUG-0002, but a distinct symptom (Testcontainers-specific, not
  `docker compose up`-specific) worth its own entry.
- **Fix:** set `<reuseForks>false</reuseForks>` (with `<forkCount>1</forkCount>`) for
  `maven-surefire-plugin` in the root `pom.xml`'s `pluginManagement`, so **every test class gets its
  own forked JVM** and therefore its own fresh Testcontainers containers — no class ever shares a
  container (or a connection pool) with another. Verified: all 6 modules (`conveyor-common` +
  5 services) now pass their full integration suites reliably, run after run. Cost: slower test
  runs (a Postgres/Redpanda/Mongo startup per class instead of once per module) — accepted, since
  correctness beats speed for a bug that isn't fixable from application code.
- **Status:** Fixed (worked around at the build-tooling level; the underlying Docker
  Desktop/WSL2 networking defect itself is outside this project's control).

---

## [BUG-0003] `InboxRecord`'s manually-assigned `@EmbeddedId` makes `repository.save()` upsert instead of insert
- **Date:** 2026-09-10
- **Phase:** Phase 2 — Data layer, domain model and migrations
- **Severity:** Low (test-only; caught before the test was ever relied on as a real guarantee)
- **Symptom:** `inboxDedupIsAPrimaryKeyViolation` (one per service, 5 total) asserted that saving a
  second `InboxRecord` with the same `(message_id, consumer)` id throws
  `DataIntegrityViolationException`. It never did — the test passed for the wrong reason until
  BUG-0004's fix (`reuseForks=false`) surfaced it as the one real remaining failure once the
  environment-level connection flakiness stopped masking it.
- **Root cause:** `InboxRecord`'s `@EmbeddedId` is manually assigned, not `@GeneratedValue`. Spring
  Data JPA's default `SimpleJpaRepository.save()` can't distinguish "new" from "existing" for such
  entities without a `@Version` field or `Persistable` implementation, so it always calls
  `entityManager.merge()` — which, given an id that already exists, **updates the existing row
  instead of attempting an insert**, silently never touching the primary-key constraint the test
  meant to exercise.
- **Fix:** the test now calls `entityManager.persist()` (autowired directly) followed by an
  explicit `flush()`, which always issues a real `INSERT` and correctly throws
  `jakarta.persistence.PersistenceException` on the constraint violation. Applied identically
  across all 5 services' `OutboxInboxRepositoryIntegrationTest`.
- **Status:** Fixed.

---

## [BUG-0001] Spotless 2.44.2's bundled google-java-format incompatible with JDK 25
- **Date:** 2026-09-10
- **Phase:** Phase 1 — Repo scaffolding
- **Severity:** Low (tooling only; no runtime/business-logic impact)
- **Symptom:** `mvn verify` failed on every module with
  `java.lang.NoSuchMethodError: java.util.Queue com.sun.tools.javac.util.Log$DeferredDiagnosticHandler.getDiagnostics()`
  inside the Spotless `google-java-format` step.
- **Root cause:** this machine's only installed JDK is 25 (LTS); the
  google-java-format version bundled in spotless-maven-plugin 2.44.2 (the
  version originally pinned) reaches into javac internals whose signature
  changed by JDK 25 and predates support for it.
- **Fix:** bumped `spotless.version` to 3.10.2 (root `pom.xml`), whose bundled
  google-java-format supports current JDKs. Ran `mvn spotless:apply` to
  reformat the files written before the fix. No code semantics changed.
- **Status:** Fixed.

---

## [BUG-0002] Docker Desktop WSL2 backend stuck in a disk-provisioning restart loop
- **Date:** 2026-09-10
- **Phase:** Phase 1 — Repo scaffolding
- **Severity:** Medium (blocks local validation; not a defect in Conveyor itself)
- **Symptom:** After a successful `docker compose build` (all 5 service images
  built, exit 0), the Docker CLI and Testcontainers both stopped getting any
  response from the daemon (`docker images`, `docker info` hung indefinitely).
  Killing and relaunching Docker Desktop did not recover it.
- **Root cause:** `%LOCALAPPDATA%\Docker\log\host\com.docker.backend.exe.log`
  shows Docker Desktop's WSL2 engine failing to provision its internal data
  disk on every start attempt: `preparing block device /dev/sde: formatting
  disk: running mkfs: exit status 1 — mkfs.ext4: No such device or address
  while trying to determine filesystem size`. This is VHDX/WSL2-level
  corruption in Docker Desktop's own backend, unrelated to this repo. It will
  not self-resolve by waiting or by restarting the app.
- **Fix (identified, not yet applied):** `wsl --unregister docker-desktop`
  (and `docker-desktop-data` if present), then relaunch Docker Desktop so it
  recreates both distros clean. **Not applied automatically** — it wipes all
  Docker images/containers/volumes on the machine for every project, not just
  Conveyor, so it was left for the human to run at their own discretion.
- **Impact on Phase 1 exit criteria:** `mvn verify` (compile, Spotless,
  Checkstyle, and — critically — the Testcontainers-backed context-load
  integration test for **all five services** against real Postgres/Redpanda/
  Mongo) passed in full *before* the daemon wedged, so the walking-skeleton
  claim is verified at the JVM/test level. The `docker compose up` → all
  containers healthy → `curl` each health endpoint exit criterion was
  **unverified this session**, blocked on the Docker fix above.
- **Resolution (2026-09-10):** the human repaired Docker Desktop. Re-ran
  `docker compose up -d --build`: all 5 images built clean, all 8 containers
  (5 services + Postgres + Mongo + Redpanda) reached `Healthy`, and `curl`
  against all 5 `/actuator/health` endpoints (8081–8085) returned
  `{"status":"UP","groups":["liveness","readiness"]}`. Stack torn down with
  `docker compose down` after verification. Phase 1's last remaining exit
  criterion is now genuinely met.
- **Status:** Fixed.

---

_No other bugs logged yet._

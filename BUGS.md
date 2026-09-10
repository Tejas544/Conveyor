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
- **Status:** Open — blocked on the human restarting Docker Desktop (or repeating BUG-0002's WSL2
  reset if a restart alone doesn't recover it). Unblocks when: `docker ps` responds, then re-run
  `./mvnw verify` for the full reactor and `docker compose up -d --build` for the live health check.

---

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

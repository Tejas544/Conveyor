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

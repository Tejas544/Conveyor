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

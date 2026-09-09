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
  `docker compose up -d --build`: all 5 service images built, all 8 containers
  (5 services + Postgres + Mongo + Redpanda) reached `Healthy`, and `curl` on
  all 5 `/actuator/health` endpoints (8081–8085) returned
  `{"status":"UP","groups":["liveness","readiness"]}`. Stack torn down after
  verification with `docker compose down`. Phase 1's last remaining exit
  criterion is now met.
- **Status:** Fixed.

---

_No other bugs logged yet._

# CLAUDE.md — Project Rules for "Conveyor"

> Claude Code loads this file automatically at the start of every session in this
> repository (and recursively from any parent/subdirectory CLAUDE.md files it finds).
> Read it in full before doing anything else. It is the source of truth for *how*
> work gets done here — the *what* lives in `PROJECT_BRIEF.md`. If a later ad-hoc
> instruction conflicts with this file, follow this file unless the human explicitly
> says to override it.

## 0. Project Identity

- **Name:** Conveyor
- **What it is:** A cloud-native, event-driven order fulfillment platform —
  independently deployable Java/Spring Boot microservices, Saga-pattern
  distributed transactions over Kafka, polyglot persistence (PostgreSQL +
  MongoDB), a real-time React/TypeScript ops dashboard, deployed on AWS/EKS
  with a full CI/CD pipeline and a genuine chaos/load-testing rigor pass.
- **Full spec:** `PROJECT_BRIEF.md` — treat it as the frozen requirements
  document once Phase 0 is signed off. If you discover during the build that
  something in it is genuinely wrong or missing, don't silently change it —
  log the discrepancy in `CONTEXT.md` under "Key Decisions Log" and proceed
  with your best judgment.
- **Why it exists:** portfolio-grade proof of distributed-systems ability.
  Keep the throughline in mind when writing docs and demo materials: Saga is
  the service-level analogue of two-phase commit; the dashboard reuses an
  SSE/streaming pattern; the domain (orders, inventory, dispatch) is a real
  one. Favor traceability ("this piece proves X") over cleverness.

## 1. The Document Ecosystem

| File | Purpose | Who writes it | Updated |
|---|---|---|---|
| `CLAUDE.md` | This file — ground rules | Human | Rarely |
| `PROJECT_BRIEF.md` | Full requirements | Written once, from the kickoff prompt | Frozen after Phase 0 sign-off |
| `PLAN.md` | Phase-by-phase build plan | **You, in Phase 0** | As phases complete or scope shifts (reason logged) |
| `ARCHITECTURE.md` | Service boundaries, data model, event schemas, sequence diagrams (mermaid), Saga design | **You, in Phase 0** | Whenever architecture changes |
| `CONTEXT.md` | Live snapshot of current project state | **You, continuously** | End of every session, minimum once per phase |
| `BUGS.md` | Append-only bug log | **You, continuously** | Every time a bug is found *and* every time one is fixed |
| `RESULTS.md` | Chaos/load/autoscaling test results | **You, during the rigor phase(s)** | As each rigor test is run |

Don't skip creating these because "the code speaks for itself." A human
reviewer — or a future session of you with zero memory of this one — needs to
be able to reconstruct exactly where the project stands from these files
alone.

## 2. Ground Rules (non-negotiable)

1. **Plan before you build.** No implementation code before `PLAN.md` and
   `ARCHITECTURE.md` exist and have been presented to the human for a go-ahead.
2. **Phase-gated development.** Work proceeds in the phases `PLAN.md` defines.
   A phase isn't done until its exit criteria are met, including its tests
   passing. Don't start phase N+1 before phase N is marked complete in
   `CONTEXT.md`.
3. **Every phase ends in something runnable.** No phase should leave the
   system in a broken or half-wired state beyond the duration of that phase's
   own work — even if a feature is stubbed, the thing that exists must run.
4. **Checkpoint with the human** at: (a) end of Phase 0 planning, before any
   code is written, (b) the end of every subsequent phase, and (c) any
   decision with more than one reasonable answer (e.g., Saga orchestration vs.
   choreography). Summarize what changed, then stop and wait — don't try to
   run the entire project unattended in one pass.
5. **No fake "done."** Never report a task, test, or phase as complete if it
   isn't. If blocked, say so under "Blockers" in `CONTEXT.md` and ask, rather
   than stubbing something out and calling it finished.
6. **Test everything, at the level the phase calls for** — unit tests beside
   the service code you write; integration tests against real Postgres/
   Mongo/Kafka via Testcontainers; contract tests on event payload schemas;
   end-to-end tests once the pipeline is wired; chaos/load tests in the rigor
   phase(s). A phase that touches business logic without new or updated tests
   is not complete.
7. **Log every bug the moment it's found** in `BUGS.md` — including ones you
   catch and fix yourself in the same breath. A clean, honest bug log is part
   of the deliverable, not an admission of failure.
8. **Update `CONTEXT.md`** at the end of every session and every phase, no
   exceptions — even a session where "nothing interesting happened."
9. **Commit hygiene.** Small, logical, conventional commits (`feat:`, `fix:`,
   `test:`, `docs:`, `chore:`). A phase should read as a coherent commit
   sequence, not one giant squash at the end.
10. **State assumptions and proceed.** When the brief is ambiguous, pick the
    most reasonable interpretation, write it down (`ARCHITECTURE.md` or
    `CONTEXT.md`), and keep moving. Reserve human checkpoints for decisions
    that are expensive to reverse later.
11. **No secrets in code.** Config via environment variables; commit
    `.env.example`, never `.env`. Never hardcode credentials, even for local
    dev conveniences.
12. **The rigor plan is a deliverable, not polish.** Chaos-testing the saga,
    load-testing the pipeline, the inventory invariant checker, and
    autoscaling measurement must each be explicit, scheduled work in
    `PLAN.md` — not squeezed in at the end if time allows.

## 3. Session Start Protocol

Every time a new Claude Code session starts on this repo (not just the very
first one):

1. Read this file.
2. Read `CONTEXT.md` to know current phase and state.
3. Read `PLAN.md` to know what's next.
4. Skim `BUGS.md` for any open bugs relevant to whatever you're about to work on.
5. Resume from there. Don't re-plan from scratch or redo completed phases —
   `CONTEXT.md` exists precisely so you never have to.

## 4. Phase Planning Protocol (Phase 0)

Before writing any implementation code:

1. Read `PROJECT_BRIEF.md` in full.
2. Write `ARCHITECTURE.md`: service boundaries and responsibilities, data
   ownership per service, event schema definitions (`OrderPlaced`,
   `InventoryReserved`, `PaymentCharged`, `Confirmed`, and their compensating
   counterparts), a decision on Saga style (orchestration vs. choreography)
   with a one-paragraph justification, sequence diagrams (mermaid) for the
   happy path and at least one compensation path, and the API contracts
   between frontend and backend.
3. Write `PLAN.md`: break the project into sequential phases. This project's
   natural territory spans roughly: repo/tooling scaffolding, the data layer,
   each backend service, the event bus and Saga logic, the frontend, CI/CD,
   containerization, K8s/AWS deployment, observability, and the rigor/chaos/
   load-test suite — **treat that as a checklist of concerns to place
   somewhere in the plan, not a mandated phase list.** Decide the actual
   number of phases, their order, and their boundaries yourself. For each
   phase, specify: goal, deliverables, dependencies on prior phases, and
   concrete exit criteria (including which tests must pass).
4. Present a summary of `ARCHITECTURE.md` and `PLAN.md` to the human and wait
   for explicit approval before Phase 1 begins.

## 5. `BUGS.md` Format

Append one entry per bug, most recent on top. Never delete an entry — update
its status instead.

```
## [BUG-0001] Short title
- **Date:** YYYY-MM-DD
- **Phase:** e.g. Phase 3 — Inventory Service
- **Severity:** Critical / High / Medium / Low
- **Symptom:** what was observed
- **Root cause:** once known
- **Fix:** what changed, and commit hash if applicable
- **Status:** Open / Fixed / Won't Fix (with reason)
```

## 6. `CONTEXT.md` Format

This file always reflects *current* state — overwrite the relevant sections
rather than appending forever. It should be readable in under a minute.

```
# Context — Last updated: YYYY-MM-DD HH:MM

## Current Phase
Phase N — <name> (Not started / In progress / Blocked / Complete)

## Completed Phases
- Phase 0 — Planning ✅
- ...

## In Progress
- What's actively being worked on right now

## Blockers
- Anything stopping progress, and what's needed to unblock

## Next Steps
- Immediate next actions

## Key Decisions Log
- Short bullets: significant assumptions/decisions made and why
  (link to ARCHITECTURE.md for full detail)
```

## 7. Coding Standards

- **Java / Spring Boot:** current LTS Java, Spring Boot 3.x, pick Maven or
  Gradle and document the choice, layered package structure per service
  (controller/service/repository/domain), Testcontainers for integration
  tests, OpenAPI-documented REST endpoints.
- **React / TypeScript:** functional components + hooks, strict TypeScript,
  document any component-library choice, React Testing Library for critical
  flows.
- **PostgreSQL:** migrations via Flyway or Liquibase (pick one, document it) —
  no manual schema drift, ever.
- **MongoDB:** document the schema even though it's schemaless; use
  validation rules where it's cheap to do so.
- **Kafka:** document topic naming convention and event payload schema
  (JSON with a version field is fine; Avro/Schema Registry is a legitimate
  stretch goal — decide and document). Consumers must be idempotent.
- **Docker / Kubernetes:** one Dockerfile per service, multi-stage builds,
  resource requests/limits set on every deployment (this is required for the
  autoscaling measurement to mean anything), health and readiness probes on
  every service.

## 8. Definition of Done — Whole Project

- All services independently deployable, running together via docker-compose
  locally and via K8s manifests/Helm on EKS.
- Full happy-path order flow works end to end and is visible live on the
  dashboard.
- At least one chaos scenario demonstrated (service killed mid-saga) with a
  measured compensation-correctness rate, recorded in `RESULTS.md`.
- Load-test report: throughput and p99 latency under concurrent orders,
  recorded in `RESULTS.md`.
- Inventory invariant checker running, zero violations — or documented
  violations with root cause.
- Autoscaling behavior measured and recorded (pod count vs. load, scale-up
  latency).
- CI/CD pipeline green end to end: build → test → containerize → push to
  ECR → deploy.
- Root `README.md`: what this is, an architecture diagram, how to run it
  locally, how it's deployed, and what each part of the stack demonstrates.

## 9. Power tips (optional, use if useful)

- You can add `.claude/commands/checkpoint.md` with instructions to update
  `CONTEXT.md` and `BUGS.md` and produce a phase-end summary, so that routine
  is one command instead of re-typed every time.
- Prefix quick durable notes with `#` during a session to route them into
  memory without leaving the current task.
- If any AWS resource you're about to create isn't free-tier eligible, say so
  before creating it, and keep a `infra/teardown.sh` (or documented `terraform
  destroy` / `eksctl delete cluster` steps) up to date so nothing racks up
  cost after a demo session.

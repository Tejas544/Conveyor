# ADR-6: Versioned JSON + JSON Schema, not Avro/Schema Registry (v1)

**Status:** Accepted (2026-09-10) — the Avro/Apicurio stretch was **cut** in Phase 16 (2026-09-12),
not built. See the reconciliation note below.

**Context:** Event payload format, and whether to run a Schema Registry.

**Decision:** Plain JSON with an explicit envelope (`ConveyorEnvelope`) and a
`schemaVersion` field. Schemas live in `conveyor-contracts` as JSON Schema
files, validated by contract tests, with a CI compatibility gate diffing
schemas against `main`.

**Consequences:** CI enforces the same backward-compatibility property a
runtime Schema Registry would, at build time, with nothing extra to run,
secure, or deploy. Avro's compact binary encoding isn't worth its operational
cost at this project's throughput. Migrating one topic to Apicurio + Avro is
an explicit, cuttable Phase 16 stretch task rather than a silent omission.

**Reconciled (Phase 16) — the stretch was cut, on purpose, not silently dropped.** The 15 phases
before this one already deliver the project's actual thesis in full: the Saga/2PC comparison, a
transactional outbox, an invariant checker with negative controls, a chaos matrix, a load test, and
an autoscaling measurement — none of which depend on wire format. Adding Apicurio + Avro on one
topic at this point would be scope for its own sake (`CLAUDE.md` §2's own rule against exactly
this), not evidence toward anything this project is actually trying to prove, and the CI
schema-compatibility gate this project already has (JSON Schema, diffed against `main`) already
demonstrates the same backward-compatibility discipline a registry would enforce at runtime — the
comparison PLAN.md's own Stretch reasoning was interested in is already made. Recorded here and in
`CONTEXT.md`'s Key Decisions Log, per this project's own standing rule that a cut stretch goal is
stated, not left to look like an oversight.

**Full reasoning:** `ARCHITECTURE.md` ADR-6.

# ADR-6: Versioned JSON + JSON Schema, not Avro/Schema Registry (v1)

**Status:** Accepted (2026-09-10) — Avro is a scheduled stretch (Phase 16)

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

**Full reasoning:** `ARCHITECTURE.md` ADR-6.

# ADR-8: Flyway for Postgres migrations

**Status:** Accepted (2026-09-10)

**Context:** Flyway vs. Liquibase for schema migrations, one folder per
service, no shared tables across services.

**Decision:** Flyway, plain versioned SQL.

**Consequences:** No manual schema drift, ever (`CLAUDE.md` §7). Liquibase's
XML/YAML changelog abstraction was rejected — it buys database portability
this project doesn't need, at the cost of reading SQL through a layer.

**Full reasoning:** `ARCHITECTURE.md` ADR-8.

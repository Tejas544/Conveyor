# ADR-12: Product catalog (MongoDB) is owned by Inventory Service

**Status:** Accepted (2026-09-10)

**Context:** Where does polyglot persistence actually live — split across
services, or justified within one?

**Decision:** Inventory Service owns both the stock ledger (PostgreSQL) and
catalog metadata (MongoDB).

**Consequences:** The polyglot-persistence justification sits inside one
bounded context, where it is sharpest: the same service stores "what a SKU
is" as a document (shape varies per category, read whole) and "how many there
are" relationally (needs a transactional `on_hand - reserved >= 0`
invariant). Assigning each database to a different service would have made
the split look arbitrary rather than reasoned.

**Full reasoning:** `ARCHITECTURE.md` ADR-12.

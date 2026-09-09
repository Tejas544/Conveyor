# ADR-9: Inventory reservation via guarded conditional UPDATE, not SELECT FOR UPDATE

**Status:** Accepted (2026-09-10)

**Context:** Reserving stock under concurrent orders must never oversell.

**Decision:** A single statement —
`UPDATE stock_items SET reserved = reserved + :qty ... WHERE on_hand - reserved >= :qty`
— plus a table `CHECK (on_hand - reserved >= 0)` constraint. Zero rows
affected means insufficient stock.

**Consequences:** Oversell becomes structurally impossible at the database
level rather than an application check-then-act race, with no lock held
across a round trip. Phase 4's exit criteria include a concurrency test: *N*
concurrent reservations against one unit of stock, exactly one succeeds.

**Full reasoning:** `ARCHITECTURE.md` ADR-9.

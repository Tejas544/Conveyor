# ADR-11: React 19 + TypeScript + Tailwind/shadcn + TanStack Query

**Status:** Accepted (2026-09-10)

**Context:** Frontend stack for the ops dashboard and admin panel.

**Decision:** React 19, strict TypeScript (`noUncheckedIndexedAccess`), Vite,
Tailwind CSS + shadcn/ui (vendored Radix primitives, not a versioned
dependency), TanStack Query for REST, a hand-written `useSagaStream` hook for
SSE, Vitest + React Testing Library, Playwright for one E2E order-placement
journey.

**Consequences:** shadcn/ui being vendored means the design is fully ours to
change rather than fighting a component library's own opinions — appropriate
for a demo-critical, highly visual dashboard (`PLAN.md` Phase 8).

**Full reasoning:** `ARCHITECTURE.md` ADR-11.

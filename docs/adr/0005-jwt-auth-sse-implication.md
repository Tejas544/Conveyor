# ADR-5: Self-issued RS256 JWT for admin-panel auth

**Status:** Accepted (2026-09-10)

**Context:** The admin panel needs auth; JWT, external IdPs (Cognito/Auth0),
and server-side sessions were the candidates.

**Decision:** Order Service issues RS256 access tokens (15 min) plus an
`HttpOnly` refresh cookie (30 days) against a local `users` table. Every
service validates the token as a Spring Security resource server against a
JWKS endpoint. Roles: `ROLE_OPS`, `ROLE_ADMIN`.

**Consequences — the one worth remembering:** browser `EventSource` cannot
send an `Authorization` header, so the dashboard's SSE client uses `fetch` +
`ReadableStream` with a hand-written frame parser instead — a deliberate
choice, and a direct reuse of the EdgeRAG streaming work, not a workaround.
External IdPs were rejected as adding setup for a demo without showing the
part actually worth demonstrating (token validation, key handling).

**Full reasoning:** `ARCHITECTURE.md` ADR-5, ADR-10.

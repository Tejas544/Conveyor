/**
 * The access token lives in memory only, never localStorage/sessionStorage -- ADR-5's whole point
 * is that a successful XSS should not be able to read a long-lived credential. The refresh token
 * (HttpOnly cookie, set by order-service) is what survives a page reload; on load this store just
 * calls /auth/refresh once to find out whether that cookie is still good.
 */
type Listener = () => void

let accessToken: string | null = null
let roles: string[] = []
const listeners = new Set<Listener>()

export function getAccessToken(): string | null {
  return accessToken
}

export function getRoles(): string[] {
  return roles
}

export function setSession(token: string | null, nextRoles: string[] = []): void {
  accessToken = token
  roles = nextRoles
  listeners.forEach((listener) => listener())
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

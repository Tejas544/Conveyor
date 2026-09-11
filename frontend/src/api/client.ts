import createClient, { type Middleware } from 'openapi-fetch'
import type { paths as OrderPaths } from './generated/order'
import type { paths as InventoryPaths } from './generated/inventory'
import type { paths as PaymentPaths } from './generated/payment'
import type { paths as SagaPaths } from './generated/saga'
import type { paths as DispatchPaths } from './generated/dispatch'
import { getAccessToken, setSession } from './tokenStore'
import { API_BASE_URLS } from './config'

let refreshInFlight: Promise<string | null> | null = null

/** Single-flight: concurrent 401s from several in-flight requests trigger exactly one refresh call. */
async function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const response = await fetch(`${API_BASE_URLS.order}/api/v1/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
        })
        if (!response.ok) {
          setSession(null)
          return null
        }
        const body = (await response.json()) as { accessToken: string; roles: string[] }
        setSession(body.accessToken, body.roles)
        return body.accessToken
      } catch {
        setSession(null)
        return null
      } finally {
        refreshInFlight = null
      }
    })()
  }
  return refreshInFlight
}

const authMiddleware: Middleware = {
  async onRequest({ request }) {
    const token = getAccessToken()
    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`)
    }
    return request
  },
  async onResponse({ request, response }) {
    if (response.status !== 401 || request.headers.has('X-Retried-After-Refresh')) {
      return response
    }
    const freshToken = await refreshAccessToken()
    if (!freshToken) {
      return response
    }
    const retried = new Request(request, {
      headers: new Headers(request.headers),
    })
    retried.headers.set('Authorization', `Bearer ${freshToken}`)
    retried.headers.set('X-Retried-After-Refresh', 'true')
    return fetch(retried)
  },
}

function withAuth<T extends object>(client: ReturnType<typeof createClient<T>>) {
  client.use(authMiddleware)
  return client
}

/**
 * springdoc represents Spring Data's `Pageable` as a nested `pageable: {page, size, sort}` object
 * in the OpenAPI schema (hence openapi-typescript generating it that way), but Spring's actual
 * `Pageable` argument resolver binds flat top-level query params (`?size=200`), not
 * `?pageable[size]=200` bracket notation -- openapi-fetch's default serializer produces the
 * latter, which Spring's resolver silently can't parse (400). Flattening `pageable`'s own keys to
 * the top level here is what the wire format actually needs.
 */
function querySerializer(queryParams: Record<string, unknown>): string {
  const flat: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(queryParams)) {
    if (key === 'pageable' && value && typeof value === 'object') {
      Object.assign(flat, value as Record<string, unknown>)
    } else {
      flat[key] = value
    }
  }
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(flat)) {
    if (value === undefined || value === null) continue
    if (Array.isArray(value)) {
      value.forEach((v) => search.append(key, String(v)))
    } else {
      search.append(key, String(value))
    }
  }
  return search.toString()
}

export const orderClient = withAuth(
  createClient<OrderPaths>({ baseUrl: API_BASE_URLS.order, querySerializer }),
)
export const inventoryClient = withAuth(
  createClient<InventoryPaths>({ baseUrl: API_BASE_URLS.inventory, querySerializer }),
)
export const paymentClient = withAuth(createClient<PaymentPaths>({ baseUrl: API_BASE_URLS.payment }))
export const sagaClient = withAuth(createClient<SagaPaths>({ baseUrl: API_BASE_URLS.saga }))
export const dispatchClient = withAuth(createClient<DispatchPaths>({ baseUrl: API_BASE_URLS.dispatch }))

export { refreshAccessToken }

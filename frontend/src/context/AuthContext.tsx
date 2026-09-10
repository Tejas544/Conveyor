import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { getAccessToken, getRoles, setSession, subscribe } from '@/api/tokenStore'
import { refreshAccessToken } from '@/api/client'

interface AuthState {
  accessToken: string | null
  roles: string[]
  isAdmin: boolean
  isOps: boolean
  status: 'loading' | 'authenticated' | 'anonymous'
}

interface AuthContextValue extends AuthState {
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [accessToken, setAccessToken] = useState(getAccessToken())
  const [roles, setRoles] = useState(getRoles())
  const [status, setStatus] = useState<AuthState['status']>('loading')

  useEffect(() => {
    return subscribe(() => {
      setAccessToken(getAccessToken())
      setRoles(getRoles())
    })
  }, [])

  useEffect(() => {
    let cancelled = false
    refreshAccessToken().then((token) => {
      if (!cancelled) {
        setStatus(token ? 'authenticated' : 'anonymous')
      }
    })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (status !== 'authenticated') return
    // Well inside the 15-minute access-token TTL (order-service's JwtIssuerProperties default).
    const id = window.setInterval(() => refreshAccessToken(), 10 * 60 * 1000)
    return () => window.clearInterval(id)
  }, [status])

  async function login(username: string, password: string) {
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ username, password }),
    })
    if (!response.ok) {
      throw new Error('Invalid username or password')
    }
    const body = (await response.json()) as { accessToken: string; roles: string[] }
    setSession(body.accessToken, body.roles)
    setStatus('authenticated')
  }

  async function logout() {
    await fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'include' })
    setSession(null)
    setStatus('anonymous')
  }

  const value: AuthContextValue = {
    accessToken,
    roles,
    isAdmin: roles.includes('ADMIN'),
    isOps: roles.includes('OPS') || roles.includes('ADMIN'),
    status,
    login,
    logout,
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'

export function ProtectedRoute({ children, requireAdmin = false }: { children: ReactNode; requireAdmin?: boolean }) {
  const { status, isOps, isAdmin } = useAuth()

  if (status === 'loading') {
    return <div className="flex h-screen items-center justify-center text-muted-foreground text-sm">Loading…</div>
  }
  if (status === 'anonymous' || !isOps) {
    return <Navigate to="/login" replace />
  }
  if (requireAdmin && !isAdmin) {
    return <Navigate to="/" replace />
  }
  return <>{children}</>
}

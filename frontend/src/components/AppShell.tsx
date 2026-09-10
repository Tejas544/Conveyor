import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export function AppShell({ children }: { children: ReactNode }) {
  const { roles, isAdmin, logout } = useAuth()
  const location = useLocation()

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center gap-6 border-b px-4 py-3">
        <span className="font-semibold">Conveyor</span>
        <nav className="flex items-center gap-4 text-sm">
          <Link
            to="/"
            className={cn(
              'text-muted-foreground hover:text-foreground',
              location.pathname === '/' && 'font-medium text-foreground',
            )}
          >
            Pipeline
          </Link>
          <Link
            to="/inventory"
            className={cn(
              'text-muted-foreground hover:text-foreground',
              location.pathname === '/inventory' && 'font-medium text-foreground',
            )}
          >
            Inventory
          </Link>
        </nav>
        <div className="ml-auto flex items-center gap-3 text-sm">
          <span className="text-muted-foreground">{isAdmin ? 'ADMIN' : roles.join(', ')}</span>
          <Button variant="ghost" size="sm" onClick={() => logout()}>
            Sign out
          </Button>
        </div>
      </header>
      <main className="flex-1 overflow-auto p-4">{children}</main>
    </div>
  )
}

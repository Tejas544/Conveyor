import { cn } from '@/lib/utils'
import type { ConnectionState } from '@/hooks/useSagaStream'

const LABEL: Record<ConnectionState, string> = {
  connecting: 'Connecting…',
  open: 'Live',
  reconnecting: 'Reconnecting…',
  closed: 'Disconnected',
}

const DOT_CLASS: Record<ConnectionState, string> = {
  connecting: 'bg-yellow-500 animate-pulse',
  open: 'bg-emerald-500',
  reconnecting: 'bg-yellow-500 animate-pulse',
  closed: 'bg-destructive',
}

export function ConnectionIndicator({ state }: { state: ConnectionState }) {
  return (
    <div className="flex items-center gap-1.5 text-xs text-muted-foreground" data-testid="connection-indicator">
      <span className={cn('inline-block size-2 rounded-full', DOT_CLASS[state])} aria-hidden />
      {LABEL[state]}
    </div>
  )
}

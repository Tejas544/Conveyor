import { AnimatePresence } from 'motion/react'
import { OrderCard, type KanbanOrder } from './OrderCard'
import { STATUS_COLOR_VAR, STATUS_LABELS, type OrderStatus } from '@/lib/orderStatus'

export function KanbanColumn({ status, orders }: { status: OrderStatus; orders: KanbanOrder[] }) {
  return (
    <div className="flex min-w-64 flex-1 flex-col gap-2 rounded-lg bg-muted/40 p-2" data-testid={`column-${status}`}>
      <div className="flex items-center gap-2 px-1 py-1">
        <span
          className="inline-block size-2.5 rounded-full"
          style={{ backgroundColor: STATUS_COLOR_VAR[status] }}
          aria-hidden
        />
        <h2 className="text-sm font-semibold">{STATUS_LABELS[status]}</h2>
        <span className="ml-auto rounded-full bg-background px-2 py-0.5 text-xs text-muted-foreground">
          {orders.length}
        </span>
      </div>
      <div className="flex min-h-16 flex-col gap-2">
        <AnimatePresence initial={false}>
          {orders.map((order) => (
            <OrderCard key={order.orderId} order={order} />
          ))}
        </AnimatePresence>
      </div>
    </div>
  )
}

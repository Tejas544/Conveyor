import { motion } from 'motion/react'
import { Link } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import type { OrderStatus } from '@/lib/orderStatus'

export interface KanbanOrder {
  orderId: string
  status: OrderStatus
  totalAmount?: number
  currency?: string
  itemCount: number
  createdAt?: string
}

export function OrderCard({ order }: { order: KanbanOrder }) {
  return (
    <motion.div layout layoutId={order.orderId} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
      <Link to={`/orders/${order.orderId}`}>
        <Card className="cursor-pointer gap-2 py-3 transition-shadow hover:shadow-md">
          <CardContent className="flex flex-col gap-1 px-3">
            <span className="font-mono text-xs text-muted-foreground">{order.orderId.slice(0, 8)}</span>
            <span className="text-sm font-medium">
              {order.itemCount} item{order.itemCount === 1 ? '' : 's'}
              {order.totalAmount != null && (
                <span className="text-muted-foreground">
                  {' '}
                  · {order.currency} {order.totalAmount.toFixed(2)}
                </span>
              )}
            </span>
          </CardContent>
        </Card>
      </Link>
    </motion.div>
  )
}

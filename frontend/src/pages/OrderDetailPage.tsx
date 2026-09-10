import { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { orderClient, dispatchClient } from '@/api/client'
import { OrderTimeline } from '@/components/timeline/OrderTimeline'
import { Badge } from '@/components/ui/badge'
import { STATUS_LABELS, type OrderStatus } from '@/lib/orderStatus'

export function OrderDetailPage() {
  const { orderId = '' } = useParams<{ orderId: string }>()
  const [order, setOrder] = useState<{
    status?: OrderStatus
    totalAmount?: number
    currency?: string
    items?: { sku?: string; quantity?: number; unitPrice?: number }[]
  } | null>(null)
  const [shipment, setShipment] = useState<{ trackingNumber?: string; carrier?: string } | null>(null)

  useEffect(() => {
    orderClient.GET('/api/v1/orders/{orderId}', { params: { path: { orderId } } }).then(({ data }) => setOrder(data ?? null))
    dispatchClient
      .GET('/api/v1/shipments/{orderId}', { params: { path: { orderId } } })
      .then(({ data }) => setShipment(data ?? null))
  }, [orderId])

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link to="/" className="text-muted-foreground text-sm hover:underline">
          ← Back to pipeline
        </Link>
        <div className="mt-1 flex items-center gap-3">
          <h1 className="font-mono text-lg font-semibold">{orderId}</h1>
          {order?.status && <Badge>{STATUS_LABELS[order.status]}</Badge>}
        </div>
      </div>

      {order && (
        <div className="rounded-lg border p-4">
          <h2 className="mb-2 text-sm font-semibold">Items</h2>
          <ul className="flex flex-col gap-1 text-sm">
            {order.items?.map((item, i) => (
              <li key={i} className="flex justify-between">
                <span>
                  {item.sku} × {item.quantity}
                </span>
                <span className="text-muted-foreground">{item.unitPrice}</span>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-sm font-medium">
            Total: {order.currency} {order.totalAmount?.toFixed(2)}
          </p>
        </div>
      )}

      {shipment && (
        <div className="rounded-lg border p-4 text-sm">
          <h2 className="mb-1 font-semibold">Shipment</h2>
          <p>
            {shipment.carrier} — <span className="font-mono">{shipment.trackingNumber}</span>
          </p>
        </div>
      )}

      <div className="rounded-lg border p-4">
        <h2 className="mb-3 text-sm font-semibold">Saga timeline</h2>
        <OrderTimeline orderId={orderId} />
      </div>
    </div>
  )
}

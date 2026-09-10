import { useCallback, useEffect, useMemo, useState } from 'react'
import { orderClient } from '@/api/client'
import { useSagaStream, type SseFrame } from '@/hooks/useSagaStream'
import { ConnectionIndicator } from '@/components/ConnectionIndicator'
import { KanbanColumn } from './KanbanColumn'
import type { KanbanOrder } from './OrderCard'
import { ORDER_STATUSES, statusFromEventType, type OrderStatus } from '@/lib/orderStatus'

export function KanbanBoard() {
  const [orders, setOrders] = useState<Map<string, KanbanOrder>>(new Map())
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    orderClient.GET('/api/v1/orders', { params: { query: { pageable: { size: 200 } } } }).then(({ data }) => {
      if (cancelled || !data?.content) return
      const next = new Map<string, KanbanOrder>()
      for (const o of data.content) {
        if (!o.orderId || !o.status) continue
        next.set(o.orderId, {
          orderId: o.orderId,
          status: o.status,
          totalAmount: o.totalAmount,
          currency: o.currency,
          itemCount: o.items?.length ?? 0,
          createdAt: o.createdAt,
        })
      }
      setOrders(next)
      setLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [])

  const handleEvent = useCallback((frame: SseFrame) => {
    let payload: { orderId?: string; eventType?: string; payload?: Record<string, unknown> }
    try {
      payload = JSON.parse(frame.data)
    } catch {
      return
    }
    const { orderId, eventType } = payload
    if (!orderId || !eventType) return
    const nextStatus = statusFromEventType(eventType)
    if (!nextStatus) return

    setOrders((prev) => {
      const next = new Map(prev)
      const existing = next.get(orderId)
      if (existing) {
        next.set(orderId, { ...existing, status: nextStatus })
      } else if (eventType === 'OrderPlaced') {
        const p = payload.payload as { items?: unknown[]; totalAmount?: number; currency?: string } | undefined
        next.set(orderId, {
          orderId,
          status: nextStatus,
          totalAmount: p?.totalAmount,
          currency: p?.currency,
          itemCount: p?.items?.length ?? 0,
        })
      }
      return next
    })
  }, [])

  const { connectionState } = useSagaStream(handleEvent)

  const byStatus = useMemo(() => {
    const buckets = new Map<OrderStatus, KanbanOrder[]>(ORDER_STATUSES.map((s) => [s, []]))
    for (const order of orders.values()) {
      buckets.get(order.status)?.push(order)
    }
    for (const list of buckets.values()) {
      list.sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
    }
    return buckets
  }, [orders])

  return (
    <div className="flex h-full flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Order Pipeline</h1>
        <ConnectionIndicator state={connectionState} />
      </div>
      {loading ? (
        <p className="text-muted-foreground text-sm">Loading orders…</p>
      ) : (
        <div className="flex flex-1 gap-3 overflow-x-auto pb-2">
          {ORDER_STATUSES.map((status) => (
            <KanbanColumn key={status} status={status} orders={byStatus.get(status) ?? []} />
          ))}
        </div>
      )}
    </div>
  )
}

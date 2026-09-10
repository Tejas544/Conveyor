export const ORDER_STATUSES = [
  'PLACED',
  'INVENTORY_RESERVED',
  'PAYMENT_CHARGED',
  'CONFIRMED',
  'COMPENSATING',
  'CANCELLED',
] as const

export type OrderStatus = (typeof ORDER_STATUSES)[number]

export const STATUS_LABELS: Record<OrderStatus, string> = {
  PLACED: 'Placed',
  INVENTORY_RESERVED: 'Inventory Reserved',
  PAYMENT_CHARGED: 'Payment Charged',
  CONFIRMED: 'Confirmed',
  COMPENSATING: 'Compensating',
  CANCELLED: 'Cancelled',
}

export const STATUS_COLOR_VAR: Record<OrderStatus, string> = {
  PLACED: 'var(--color-state-placed)',
  INVENTORY_RESERVED: 'var(--color-state-reserved)',
  PAYMENT_CHARGED: 'var(--color-state-charged)',
  CONFIRMED: 'var(--color-state-confirmed)',
  COMPENSATING: 'var(--color-state-compensating)',
  CANCELLED: 'var(--color-state-cancelled)',
}

/** ARCHITECTURE.md §10.1 SSE event catalogue -> a human step label for the timeline. */
export function stepLabel(eventType: string): string {
  switch (eventType) {
    case 'OrderPlaced':
      return 'Order placed'
    case 'InventoryReserved':
      return 'Inventory reserved'
    case 'InventoryReservationFailed':
      return 'Inventory reservation failed'
    case 'InventoryReleased':
      return 'Inventory released (compensation)'
    case 'PaymentCharged':
      return 'Payment charged'
    case 'PaymentFailed':
      return 'Payment failed'
    case 'PaymentRefunded':
      return 'Payment refunded (compensation)'
    case 'OrderConfirmed':
      return 'Order confirmed'
    case 'OrderCancelled':
      return 'Order cancelled'
    case 'ShipmentCreated':
      return 'Shipment created'
    default:
      return eventType
  }
}

export function isCompensationStep(eventType: string): boolean {
  return eventType === 'InventoryReleased' || eventType === 'PaymentRefunded' || eventType === 'OrderCancelled'
}

/** Mirrors order-service's own SagaEventProjectionListener#mapToStatus -- same source of truth, client side. */
export function statusFromEventType(eventType: string): OrderStatus | null {
  switch (eventType) {
    case 'OrderPlaced':
      return 'PLACED'
    case 'InventoryReserved':
      return 'INVENTORY_RESERVED'
    case 'PaymentCharged':
      return 'PAYMENT_CHARGED'
    case 'OrderConfirmed':
      return 'CONFIRMED'
    case 'OrderCancelled':
      return 'CANCELLED'
    case 'InventoryReservationFailed':
    case 'PaymentFailed':
      return 'COMPENSATING'
    default:
      return null
  }
}

import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { ReactNode } from 'react'
import { KanbanBoard } from './KanbanBoard'

// Animation completion is driven by rAF timing that JSDOM doesn't run, which would otherwise
// leave "exiting" cards in the DOM indefinitely during a test. Tests care about final state, not
// the animation itself.
vi.mock('motion/react', () => ({
  motion: new Proxy(
    {},
    {
      get:
        () =>
        ({ children, ...props }: { children?: ReactNode }) => {
          const { layout: _layout, layoutId: _layoutId, initial: _initial, animate: _animate, exit: _exit, transition: _transition, ...rest } = props as Record<string, unknown>
          return <div {...rest}>{children}</div>
        },
    },
  ),
  AnimatePresence: ({ children }: { children?: ReactNode }) => <>{children}</>,
}))

const handleEventRef: { current: ((frame: { data: string; event: string; id: string | null }) => void) | null } = {
  current: null,
}

vi.mock('@/hooks/useSagaStream', () => ({
  useSagaStream: (onEvent: (frame: { data: string; event: string; id: string | null }) => void) => {
    handleEventRef.current = onEvent
    return { connectionState: 'open' }
  },
}))

vi.mock('@/api/client', () => ({
  orderClient: {
    GET: vi.fn().mockResolvedValue({
      data: {
        content: [
          {
            orderId: '11111111-1111-1111-1111-111111111111',
            status: 'PLACED',
            totalAmount: 19.98,
            currency: 'USD',
            items: [{ sku: 'SKU-1', quantity: 2, unitPrice: 9.99 }],
            createdAt: '2026-01-01T00:00:00Z',
          },
        ],
      },
      error: undefined,
    }),
  },
}))

describe('KanbanBoard', () => {
  beforeEach(() => {
    handleEventRef.current = null
  })

  it('renders the initial order in its status column and moves it on a live SSE event', async () => {
    render(
      <MemoryRouter>
        <KanbanBoard />
      </MemoryRouter>,
    )

    await waitFor(() => expect(screen.getByTestId('column-PLACED')).toBeInTheDocument())
    const placedColumn = screen.getByTestId('column-PLACED')
    const reservedColumn = screen.getByTestId('column-INVENTORY_RESERVED')

    await waitFor(() => expect(placedColumn).toHaveTextContent('11111111'))
    expect(reservedColumn).not.toHaveTextContent('11111111')

    expect(handleEventRef.current).not.toBeNull()
    handleEventRef.current!({
      id: '1',
      event: 'order.step',
      data: JSON.stringify({
        orderId: '11111111-1111-1111-1111-111111111111',
        eventType: 'InventoryReserved',
        payload: {},
      }),
    })

    await waitFor(() => expect(reservedColumn).toHaveTextContent('11111111'))
    expect(placedColumn).not.toHaveTextContent('11111111')
  })
})

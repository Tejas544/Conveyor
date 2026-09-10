import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AuthContext } from '@/context/AuthContext'
import { InventoryPage } from './InventoryPage'

vi.mock('@/api/client', () => ({
  inventoryClient: {
    GET: vi.fn().mockResolvedValue({
      data: { content: [{ sku: 'SKU-1', onHand: 10, reserved: 2, available: 8, reorderLevel: 5 }] },
      error: undefined,
    }),
  },
}))

function renderWithAuth(isAdmin: boolean) {
  const value = {
    accessToken: 'token',
    roles: isAdmin ? ['OPS', 'ADMIN'] : ['OPS'],
    isAdmin,
    isOps: true,
    status: 'authenticated' as const,
    login: vi.fn(),
    logout: vi.fn(),
  }
  return render(
    <AuthContext.Provider value={value}>
      <InventoryPage />
    </AuthContext.Provider>,
  )
}

describe('InventoryPage admin gating', () => {
  it('hides the Adjust action for an OPS-only user', async () => {
    renderWithAuth(false)
    await waitFor(() => expect(screen.getByText('SKU-1')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /adjust/i })).not.toBeInTheDocument()
  })

  it('shows the Adjust action for an ADMIN user', async () => {
    renderWithAuth(true)
    await waitFor(() => expect(screen.getByText('SKU-1')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /adjust/i })).toBeInTheDocument()
  })
})

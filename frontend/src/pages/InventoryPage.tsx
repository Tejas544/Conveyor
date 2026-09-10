import { useCallback, useEffect, useState } from 'react'
import { inventoryClient } from '@/api/client'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { AdjustStockDialog } from '@/components/admin/AdjustStockDialog'
import { cn } from '@/lib/utils'

interface InventoryRow {
  sku?: string
  onHand?: number
  reserved?: number
  available?: number
  reorderLevel?: number
  catalog?: { name?: string; category?: string }
}

export function InventoryPage() {
  const { isAdmin } = useAuth()
  const [rows, setRows] = useState<InventoryRow[]>([])
  const [loading, setLoading] = useState(true)
  const [adjustingSku, setAdjustingSku] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    inventoryClient.GET('/api/v1/inventory', { params: { query: { pageable: { size: 200 } } } }).then(({ data }) => {
      setRows(data?.content ?? [])
      setLoading(false)
    })
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-lg font-semibold">Inventory</h1>
      {loading ? (
        <p className="text-muted-foreground text-sm">Loading…</p>
      ) : (
        <div className="overflow-hidden rounded-lg border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left text-xs text-muted-foreground">
              <tr>
                <th className="px-3 py-2 font-medium">SKU</th>
                <th className="px-3 py-2 font-medium">Name</th>
                <th className="px-3 py-2 font-medium">On hand</th>
                <th className="px-3 py-2 font-medium">Reserved</th>
                <th className="px-3 py-2 font-medium">Available</th>
                {isAdmin && <th className="px-3 py-2 font-medium">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const low = (row.available ?? 0) <= (row.reorderLevel ?? 0)
                return (
                  <tr key={row.sku} className="border-t">
                    <td className="px-3 py-2 font-mono text-xs">{row.sku}</td>
                    <td className="px-3 py-2">{row.catalog?.name ?? '—'}</td>
                    <td className="px-3 py-2">{row.onHand}</td>
                    <td className="px-3 py-2">{row.reserved}</td>
                    <td className={cn('px-3 py-2', low && 'font-semibold text-destructive')}>
                      {row.available}
                      {low && (
                        <Badge variant="destructive" className="ml-2 text-[10px]">
                          Low stock
                        </Badge>
                      )}
                    </td>
                    {isAdmin && (
                      <td className="px-3 py-2">
                        <Button size="sm" variant="outline" onClick={() => setAdjustingSku(row.sku ?? null)}>
                          Adjust
                        </Button>
                      </td>
                    )}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      <AdjustStockDialog
        sku={adjustingSku}
        open={adjustingSku !== null}
        onOpenChange={(open) => !open && setAdjustingSku(null)}
        onAdjusted={load}
      />
    </div>
  )
}

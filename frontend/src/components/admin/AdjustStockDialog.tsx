import { useState, type FormEvent } from 'react'
import { inventoryClient } from '@/api/client'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
  DialogClose,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export function AdjustStockDialog({
  sku,
  open,
  onOpenChange,
  onAdjusted,
}: {
  sku: string | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onAdjusted: () => void
}) {
  const [delta, setDelta] = useState('0')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!sku) return
    setError(null)
    setSubmitting(true)
    const { error: apiError } = await inventoryClient.POST('/api/v1/inventory/{sku}/adjust', {
      params: { path: { sku } },
      body: { delta: Number(delta), reason },
    })
    setSubmitting(false)
    if (apiError) {
      setError('Adjustment failed -- check the delta doesn\'t take on-hand negative.')
      return
    }
    setDelta('0')
    setReason('')
    onAdjusted()
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Adjust stock — {sku}</DialogTitle>
          <DialogDescription>Writes an audited stock_adjustments row (ADMIN only).</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="delta">Delta (+/-)</Label>
            <Input id="delta" type="number" value={delta} onChange={(e) => setDelta(e.target.value)} required />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="reason">Reason</Label>
            <Input id="reason" value={reason} onChange={(e) => setReason(e.target.value)} required />
          </div>
          {error && <p className="text-destructive text-sm">{error}</p>}
          <DialogFooter>
            <DialogClose asChild>
              <Button type="button" variant="outline">
                Cancel
              </Button>
            </DialogClose>
            <Button type="submit" disabled={submitting}>
              {submitting ? 'Saving…' : 'Save adjustment'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

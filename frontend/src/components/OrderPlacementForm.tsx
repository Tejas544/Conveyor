import { useEffect, useState, type FormEvent } from 'react'
import { orderClient, inventoryClient } from '@/api/client'
import { API_BASE_URLS } from '@/api/config'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogTrigger } from '@/components/ui/dialog'

interface SkuOption {
  sku: string
  available: number
}

export function OrderPlacementForm() {
  const [open, setOpen] = useState(false)
  const [skus, setSkus] = useState<SkuOption[]>([])
  const [sku, setSku] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [unitPrice, setUnitPrice] = useState('9.99')
  const [forceFailure, setForceFailure] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!open) return
    inventoryClient.GET('/api/v1/inventory', { params: { query: { pageable: { size: 100 } } } }).then(({ data }) => {
      const options =
        data?.content?.flatMap((r) => (r.sku && r.available != null ? [{ sku: r.sku, available: r.available }] : [])) ?? []
      setSkus(options)
      if (options.length > 0 && options[0]) setSku((current) => current || options[0]!.sku)
    })
  }, [open])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setResult(null)
    setSubmitting(true)

    if (forceFailure) {
      // ARCHITECTURE.md §10.4: chaos-profile-only test surface. Best-effort -- if the stack isn't
      // running with SPRING_PROFILES_ACTIVE=chaos on payment-service this simply 404s, and the
      // order below still goes through normally rather than silently failing to place at all.
      await fetch(`${API_BASE_URLS.payment}/test/failure-mode`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'DECLINE', probability: 1.0 }),
      }).catch(() => undefined)
    }

    const { data, error: apiError } = await orderClient.POST('/api/v1/orders', {
      body: {
        customerId: crypto.randomUUID(),
        items: [{ sku, quantity: Number(quantity), unitPrice: Number(unitPrice) }],
        shippingAddress: { line1: '1 Demo St', city: 'Testville', postalCode: '00000', country: 'IN' },
        currency: 'USD',
        paymentMethodToken: 'tok_test_visa',
      },
    })

    setSubmitting(false)
    if (apiError || !data) {
      setError('Could not place order -- check the SKU has stock.')
      return
    }
    setResult(`Order ${data.orderId} placed.`)
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>Place order</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Place a demo order</DialogTitle>
          <DialogDescription>Watch it move across the kanban board in real time.</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="sku">SKU</Label>
            <select
              id="sku"
              value={sku}
              onChange={(e) => setSku(e.target.value)}
              className="border-input h-9 rounded-md border bg-transparent px-3 text-sm"
              required
            >
              {skus.map((s) => (
                <option key={s.sku} value={s.sku}>
                  {s.sku} ({s.available} available)
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="quantity">Quantity</Label>
              <Input id="quantity" type="number" min={1} value={quantity} onChange={(e) => setQuantity(e.target.value)} required />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="unitPrice">Unit price</Label>
              <Input
                id="unitPrice"
                type="number"
                step="0.01"
                min={0}
                value={unitPrice}
                onChange={(e) => setUnitPrice(e.target.value)}
                required
              />
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={forceFailure} onChange={(e) => setForceFailure(e.target.checked)} />
            Make this one fail (forces a payment decline — requires the chaos profile)
          </label>
          {error && <p className="text-destructive text-sm">{error}</p>}
          {result && <p className="text-sm text-emerald-600">{result}</p>}
          <Button type="submit" disabled={submitting || !sku}>
            {submitting ? 'Placing…' : 'Place order'}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  )
}

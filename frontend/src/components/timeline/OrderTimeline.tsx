import { useCallback, useEffect, useState } from 'react'
import { motion } from 'motion/react'
import { sagaClient } from '@/api/client'
import { useSagaStream } from '@/hooks/useSagaStream'
import { ConnectionIndicator } from '@/components/ConnectionIndicator'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

interface SagaStep {
  seq?: number
  step?: string
  direction?: string
  status?: string
  occurredAt?: string
}

interface SagaDetail {
  sagaId?: string
  state?: string
  currentStep?: string
  failureReason?: string
  steps?: SagaStep[]
}

export function OrderTimeline({ orderId }: { orderId: string }) {
  const [saga, setSaga] = useState<SagaDetail | null>(null)
  const [notFound, setNotFound] = useState(false)

  const fetchSaga = useCallback(() => {
    sagaClient.GET('/api/v1/sagas/{orderId}', { params: { path: { orderId } } }).then(({ data, error }) => {
      if (data) {
        setSaga(data)
      } else if (error) {
        setNotFound(true)
      }
    })
  }, [orderId])

  useEffect(() => {
    fetchSaga()
  }, [fetchSaga])

  const handleEvent = useCallback(() => {
    fetchSaga()
  }, [fetchSaga])

  const { connectionState } = useSagaStream(handleEvent, orderId)

  if (notFound && !saga) {
    return <p className="text-muted-foreground text-sm">No saga has started for this order yet.</p>
  }
  if (!saga) {
    return <p className="text-muted-foreground text-sm">Loading timeline…</p>
  }

  const steps = [...(saga.steps ?? [])].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">Saga state:</span>
          <Badge variant={saga.state === 'NEEDS_INTERVENTION' ? 'destructive' : 'secondary'}>{saga.state}</Badge>
        </div>
        <ConnectionIndicator state={connectionState} />
      </div>
      <ol className="flex flex-col gap-0">
        {steps.map((step, index) => {
          const isCompensation = step.direction === 'COMPENSATION'
          return (
            <motion.li
              key={`${step.seq}-${step.step}`}
              initial={{ opacity: 0, x: -8 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: index * 0.03 }}
              className="relative flex gap-3 pb-6 last:pb-0"
            >
              <div className="flex flex-col items-center">
                <span
                  className={cn(
                    'z-10 flex size-3 shrink-0 rounded-full ring-4 ring-background',
                    isCompensation ? 'bg-state-compensating' : 'bg-state-confirmed',
                  )}
                  style={{
                    backgroundColor: isCompensation ? 'var(--color-state-compensating)' : 'var(--color-state-confirmed)',
                  }}
                />
                {index < steps.length - 1 && <span className="w-px flex-1 bg-border" />}
              </div>
              <div className="flex flex-col gap-0.5">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{step.step}</span>
                  <Badge variant={isCompensation ? 'destructive' : 'outline'} className="text-[10px]">
                    {step.direction}
                  </Badge>
                  <Badge variant="secondary" className="text-[10px]">
                    {step.status}
                  </Badge>
                </div>
                {step.occurredAt && (
                  <span className="text-muted-foreground text-xs">{new Date(step.occurredAt).toLocaleString()}</span>
                )}
              </div>
            </motion.li>
          )
        })}
        {steps.length === 0 && <p className="text-muted-foreground text-sm">No steps recorded yet.</p>}
      </ol>
      {saga.failureReason && (
        <p className="text-destructive text-sm">
          <strong>Failure reason:</strong> {saga.failureReason}
        </p>
      )}
    </div>
  )
}

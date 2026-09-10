import { useEffect, useRef, useState } from 'react'
import { getAccessToken } from '@/api/tokenStore'

export type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface SseFrame {
  id: string | null
  event: string
  data: string
}

const MAX_BACKOFF_MS = 30_000
const BASE_BACKOFF_MS = 1_000

/**
 * ADR-5: the browser's native EventSource can't send an Authorization header, so this hand-rolls
 * SSE over fetch + ReadableStream instead -- a normal request with a Bearer header, manual frame
 * parsing, Last-Event-ID resume, and exponential-backoff reconnect. Direct reuse of the EdgeRAG
 * streaming pattern the architecture doc calls out.
 */
export function useSagaStream(onEvent: (frame: SseFrame) => void, orderId?: string) {
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
  const onEventRef = useRef(onEvent)
  onEventRef.current = onEvent

  useEffect(() => {
    const abortController = new AbortController()
    let lastEventId: string | null = null
    let backoff = BASE_BACKOFF_MS
    let stopped = false
    let retryTimer: number | undefined

    async function connectOnce() {
      setConnectionState((prev) => (prev === 'connecting' ? prev : 'reconnecting'))
      const url = orderId ? `/api/v1/stream/orders?orderId=${encodeURIComponent(orderId)}` : '/api/v1/stream/orders'
      const headers: Record<string, string> = {}
      const token = getAccessToken()
      if (token) headers.Authorization = `Bearer ${token}`
      if (lastEventId) headers['Last-Event-ID'] = lastEventId

      const response = await fetch(url, { headers, signal: abortController.signal })
      if (!response.ok || !response.body) {
        throw new Error(`SSE connect failed: ${response.status}`)
      }
      setConnectionState('open')
      backoff = BASE_BACKOFF_MS

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (!stopped) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        let boundary: number
        while ((boundary = buffer.indexOf('\n\n')) !== -1) {
          const rawBlock = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          const frame = parseBlock(rawBlock)
          if (!frame) continue
          if (frame.id) lastEventId = frame.id
          if (frame.event !== 'heartbeat') onEventRef.current(frame)
        }
      }
    }

    async function loop() {
      while (!stopped) {
        try {
          await connectOnce()
          if (stopped) return
          // The server closed the stream cleanly -- reconnect promptly, no backoff needed.
          backoff = BASE_BACKOFF_MS
        } catch (err) {
          if (abortController.signal.aborted) return
          setConnectionState('reconnecting')
        }
        if (stopped) return
        await new Promise<void>((resolve) => {
          retryTimer = window.setTimeout(resolve, backoff)
        })
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS)
      }
    }

    loop()

    return () => {
      stopped = true
      abortController.abort()
      if (retryTimer) window.clearTimeout(retryTimer)
      setConnectionState('closed')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId])

  return { connectionState }
}

function parseBlock(block: string): SseFrame | null {
  let id: string | null = null
  let event = 'message'
  const dataLines: string[] = []

  for (const line of block.split('\n')) {
    if (line.startsWith('id:')) id = line.slice(3).trim()
    else if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5))
  }

  if (dataLines.length === 0) return null
  return { id, event, data: dataLines.join('\n') }
}

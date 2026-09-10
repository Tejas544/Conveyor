import { renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { useSagaStream, type SseFrame } from './useSagaStream'

function streamFrom(text: string): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  const bytes = encoder.encode(text)
  let sent = false
  return new ReadableStream({
    pull(controller) {
      if (!sent) {
        controller.enqueue(bytes)
        sent = true
      } else {
        controller.close()
      }
    },
  })
}

describe('useSagaStream', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reconnects after a dropped stream and resumes from Last-Event-ID without duplicating or losing events', async () => {
    const received: SseFrame[] = []
    const requestHeaders: Record<string, string>[] = []

    const fetchMock = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      requestHeaders.push({ ...(init?.headers as Record<string, string> | undefined) })
      const callNumber = requestHeaders.length
      const body =
        callNumber === 1
          ? 'id:1\nevent:order.step\ndata:{"n":1}\n\n'
          : 'id:2\nevent:order.step\ndata:{"n":2}\n\n'
      return new Response(streamFrom(body), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { result, unmount } = renderHook(() => useSagaStream((frame) => received.push(frame)))

    await waitFor(() => expect(received).toHaveLength(1))
    expect(result.current.connectionState).toBe('open')

    // The first stream closed cleanly (simulating a dropped connection); the reconnect loop
    // fires again after the base 1s backoff -- a real, if small, wait rather than fake timers,
    // which fight with Testing Library's own polling-based waitFor.
    await waitFor(() => expect(received).toHaveLength(2), { timeout: 3_000 })

    expect(received[0]?.data).toBe('{"n":1}')
    expect(received[1]?.data).toBe('{"n":2}')

    expect(requestHeaders[0]?.['Last-Event-ID']).toBeUndefined()
    expect(requestHeaders[1]?.['Last-Event-ID']).toBe('1')

    unmount()
  }, 10_000)
})

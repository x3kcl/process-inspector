// The app's ONE live channel (v1.x #2, live-ui-sse doctrine): a single EventSource,
// context-provided — components subscribe to the named events they care about, never
// their own connection. The stream carries ID-ONLY signals; subscribers refetch their
// own JSON (debounced), so a 5000-item fan-out never pushes 5000 payloads per browser.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'

type LiveHandler = (data: string) => void

interface LiveContextValue {
  /** True while the stream is open — polling fallbacks relax when it is. */
  live: boolean
  subscribe: (event: string, handler: LiveHandler) => () => void
}

const LiveContext = createContext<LiveContextValue>({
  live: false,
  subscribe: () => () => undefined,
})

export const LIVE_STREAM_URL = '/api/bulk/events'

export function LiveProvider({ enabled, children }: { enabled: boolean; children: ReactNode }) {
  const [live, setLive] = useState(false)
  const sourceRef = useRef<EventSource | null>(null)
  const handlersRef = useRef(new Map<string, Set<LiveHandler>>())
  // Event names already wired onto the CURRENT EventSource (re-wired per reconnect).
  const wiredRef = useRef(new Set<string>())

  const wire = useCallback((source: EventSource, event: string) => {
    if (wiredRef.current.has(event)) return
    wiredRef.current.add(event)
    source.addEventListener(event, (message) => {
      const data = (message as MessageEvent<unknown>).data
      for (const handler of handlersRef.current.get(event) ?? []) {
        handler(typeof data === 'string' ? data : '')
      }
    })
  }, [])

  useEffect(() => {
    if (!enabled) return
    // First paint stays fetch-first (queries own their initial state); the stream only
    // signals change. EventSource reconnects on its own — onerror just flips the flag.
    const source = new EventSource(LIVE_STREAM_URL)
    sourceRef.current = source
    wiredRef.current = new Set()
    source.onopen = () => {
      setLive(true)
    }
    source.onerror = () => {
      setLive(false)
    }
    for (const event of handlersRef.current.keys()) wire(source, event)
    return () => {
      sourceRef.current = null
      wiredRef.current = new Set()
      setLive(false)
      source.close()
    }
  }, [enabled, wire])

  const value = useMemo<LiveContextValue>(
    () => ({
      live,
      subscribe: (event, handler) => {
        let set = handlersRef.current.get(event)
        if (set === undefined) {
          set = new Set()
          handlersRef.current.set(event, set)
        }
        set.add(handler)
        if (sourceRef.current !== null) wire(sourceRef.current, event)
        return () => {
          set.delete(handler)
        }
      },
    }),
    [live, wire],
  )

  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>
}

export function useLive(): boolean {
  return useContext(LiveContext).live
}

/**
 * Subscribe to one named stream event. Bursty by design (one signal per settled bulk
 * item) — the handler fires debounced with the LATEST data value.
 */
export function useLiveEvent(event: string, handler: LiveHandler, debounceMs = 500): void {
  const { subscribe } = useContext(LiveContext)
  const handlerRef = useRef(handler)
  handlerRef.current = handler

  useEffect(() => {
    let timer: number | undefined
    let latest = ''
    const unsubscribe = subscribe(event, (data) => {
      latest = data
      if (timer !== undefined) return
      timer = window.setTimeout(() => {
        timer = undefined
        handlerRef.current(latest)
      }, debounceMs)
    })
    return () => {
      unsubscribe()
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [event, subscribe, debounceMs])
}

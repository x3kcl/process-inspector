// Browser glue for the versioned localStorage payloads (model.ts holds the pure logic).
// One store per key. read() memoizes on the raw string so useSyncExternalStore gets a
// stable snapshot reference; the window "storage" event keeps parallel tabs in sync.
import { decodeEnvelope, encodeEnvelope } from './model'

export interface LocalStore<T> {
  read: () => T[]
  write: (items: T[]) => void
  subscribe: (onChange: () => void) => () => void
}

export function createLocalStore<T>(
  key: string,
  isItem: (value: unknown) => value is T,
): LocalStore<T> {
  let cachedRaw: string | null = null
  let cachedItems: T[] = []
  let primed = false
  const listeners = new Set<() => void>()

  const read = (): T[] => {
    const raw = localStorage.getItem(key)
    if (!primed || raw !== cachedRaw) {
      cachedRaw = raw
      cachedItems = decodeEnvelope(raw, isItem)
      primed = true
    }
    return cachedItems
  }

  const notify = () => {
    for (const listener of listeners) listener()
  }

  // event.key === null is a full storage clear — that hits us too.
  const onStorage = (event: StorageEvent) => {
    if (event.key === key || event.key === null) notify()
  }

  return {
    read,
    write: (items: T[]) => {
      localStorage.setItem(key, encodeEnvelope(items))
      notify()
    },
    subscribe: (onChange: () => void) => {
      if (listeners.size === 0) window.addEventListener('storage', onStorage)
      listeners.add(onChange)
      return () => {
        listeners.delete(onChange)
        if (listeners.size === 0) window.removeEventListener('storage', onStorage)
      }
    },
  }
}

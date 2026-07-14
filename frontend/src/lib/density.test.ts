// @vitest-environment jsdom
// R-UXQ-09 (#104 slice 3/6): the persisted grid-density store — mirrors theme.test.ts's
// coverage (default, persistence, corrupt-value fallback, private-mode survival, subscriber
// notify/unsubscribe), plus this store's own job: applying the resolved preference onto
// <html data-density>.
import { afterEach, describe, expect, it, vi } from 'vitest'

function stubStorage(initial: Record<string, string> = {}): Map<string, string> {
  const store = new Map(Object.entries(initial))
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, value)
    },
  })
  return store
}

/** Fresh module instance so each test exercises the boot-time localStorage read + apply. */
async function freshDensity(): Promise<typeof import('./density')> {
  vi.resetModules()
  return import('./density')
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
  document.documentElement.removeAttribute('data-density')
})

describe('density preference store (R-UXQ-09)', () => {
  it('defaults to comfortable, with no data-density attribute applied', async () => {
    stubStorage()
    const d = await freshDensity()
    expect(d.getDensity()).toBe('comfortable')
    expect(document.documentElement.hasAttribute('data-density')).toBe(false)
  })

  it('setting compact applies data-density="compact" to <html>', async () => {
    stubStorage()
    const d = await freshDensity()
    d.setDensity('compact')
    expect(document.documentElement.dataset['density']).toBe('compact')
  })

  it('returning to comfortable REMOVES data-density entirely', async () => {
    stubStorage()
    const d = await freshDensity()
    d.setDensity('compact')
    expect(document.documentElement.hasAttribute('data-density')).toBe(true)
    d.setDensity('comfortable')
    expect(document.documentElement.hasAttribute('data-density')).toBe(false)
  })

  it('applies the stored preference at module load, before any setter call', async () => {
    stubStorage({ 'inspector.density': 'compact' })
    await freshDensity()
    expect(document.documentElement.dataset['density']).toBe('compact')
  })

  it('persists the choice and reads it back on the next boot', async () => {
    const store = stubStorage()
    const first = await freshDensity()
    first.setDensity('compact')
    expect(store.get('inspector.density')).toBe('compact')
    const second = await freshDensity() // fresh module = fresh browser session
    expect(second.getDensity()).toBe('compact')
  })

  it('falls back to comfortable on a corrupt stored value', async () => {
    stubStorage({ 'inspector.density': 'garbage' })
    const d = await freshDensity()
    expect(d.getDensity()).toBe('comfortable')
  })

  it('survives an unavailable localStorage (private mode) — in-memory + DOM still work', async () => {
    // No stub: jsdom itself does have localStorage, so explicitly break it to simulate
    // private-mode throwing, mirroring theme.test.ts's node-has-none case.
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    })
    const d = await freshDensity()
    expect(d.getDensity()).toBe('comfortable')
    expect(() => {
      d.setDensity('compact')
    }).not.toThrow()
    expect(d.getDensity()).toBe('compact')
    expect(document.documentElement.dataset['density']).toBe('compact')
  })

  it('notifies subscribers on change and stops after unsubscribe', async () => {
    stubStorage()
    const d = await freshDensity()
    const listener = vi.fn()
    const unsubscribe = d.subscribeDensity(listener)
    d.setDensity('compact')
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
    d.setDensity('comfortable')
    expect(listener).toHaveBeenCalledTimes(1)
  })
})

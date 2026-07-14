// @vitest-environment jsdom
// R-UXQ-08 (#104 slice 2b): the persisted theme-preference store — mirrors format.test.ts's
// display-zone coverage (default, persistence, corrupt-value fallback, private-mode
// survival, subscriber notify/unsubscribe), plus this store's own extra job: applying the
// resolved preference onto <html data-theme>.
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
async function freshTheme(): Promise<typeof import('./theme')> {
  vi.resetModules()
  return import('./theme')
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
  document.documentElement.removeAttribute('data-theme')
})

describe('theme preference store (R-UXQ-08)', () => {
  it('defaults to system, with no data-theme attribute applied', async () => {
    stubStorage()
    const t = await freshTheme()
    expect(t.getThemePreference()).toBe('system')
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('setting dark applies data-theme="dark" to <html>', async () => {
    stubStorage()
    const t = await freshTheme()
    t.setThemePreference('dark')
    expect(document.documentElement.dataset['theme']).toBe('dark')
  })

  it('setting light applies data-theme="light" to <html>', async () => {
    stubStorage()
    const t = await freshTheme()
    t.setThemePreference('light')
    expect(document.documentElement.dataset['theme']).toBe('light')
  })

  it('returning to system REMOVES data-theme entirely (lets the media query decide)', async () => {
    stubStorage()
    const t = await freshTheme()
    t.setThemePreference('dark')
    expect(document.documentElement.hasAttribute('data-theme')).toBe(true)
    t.setThemePreference('system')
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('applies the stored preference at module load, before any setter call', async () => {
    stubStorage({ 'inspector.theme': 'dark' })
    await freshTheme()
    expect(document.documentElement.dataset['theme']).toBe('dark')
  })

  it('persists the choice and reads it back on the next boot', async () => {
    const store = stubStorage()
    const first = await freshTheme()
    first.setThemePreference('dark')
    expect(store.get('inspector.theme')).toBe('dark')
    const second = await freshTheme() // fresh module = fresh browser session
    expect(second.getThemePreference()).toBe('dark')
  })

  it('falls back to system on a corrupt stored value', async () => {
    stubStorage({ 'inspector.theme': 'garbage' })
    const t = await freshTheme()
    expect(t.getThemePreference()).toBe('system')
  })

  it('survives an unavailable localStorage (private mode) — in-memory + DOM still work', async () => {
    // No stub: jsdom itself does have localStorage, so explicitly break it to simulate
    // private-mode throwing, mirroring format.test.ts's node-has-none case.
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    })
    const t = await freshTheme()
    expect(t.getThemePreference()).toBe('system')
    expect(() => {
      t.setThemePreference('dark')
    }).not.toThrow()
    expect(t.getThemePreference()).toBe('dark')
    expect(document.documentElement.dataset['theme']).toBe('dark')
  })

  it('notifies subscribers on change and stops after unsubscribe', async () => {
    stubStorage()
    const t = await freshTheme()
    const listener = vi.fn()
    const unsubscribe = t.subscribeThemePreference(listener)
    t.setThemePreference('dark')
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
    t.setThemePreference('light')
    expect(listener).toHaveBeenCalledTimes(1)
  })
})

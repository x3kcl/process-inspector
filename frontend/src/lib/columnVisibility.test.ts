// @vitest-environment jsdom
// R-UXQ-09 (#104 slice 4/6): the persisted column-visibility store — mirrors density.test.ts's
// coverage (default, persistence, corrupt-value fallback, private-mode survival, subscriber
// notify/unsubscribe), adapted for a SET shape rather than a single enum value.
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

/** Fresh module instance so each test exercises the boot-time localStorage read. */
async function freshColumnVisibility(): Promise<typeof import('./columnVisibility')> {
  vi.resetModules()
  return import('./columnVisibility')
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('column-visibility store (R-UXQ-09)', () => {
  it('defaults to an empty set — nothing hidden, matching today’s behavior', async () => {
    stubStorage()
    const cv = await freshColumnVisibility()
    expect(cv.getHiddenColumns().size).toBe(0)
  })

  it('hiding a column adds it to the set', async () => {
    stubStorage()
    const cv = await freshColumnVisibility()
    cv.setColumnHidden('businessKey', true)
    expect(cv.getHiddenColumns().has('businessKey')).toBe(true)
  })

  it('un-hiding a column removes it from the set', async () => {
    stubStorage()
    const cv = await freshColumnVisibility()
    cv.setColumnHidden('businessKey', true)
    cv.setColumnHidden('businessKey', false)
    expect(cv.getHiddenColumns().has('businessKey')).toBe(false)
  })

  it('tracks multiple hidden columns independently', async () => {
    stubStorage()
    const cv = await freshColumnVisibility()
    cv.setColumnHidden('businessKey', true)
    cv.setColumnHidden('startTime', true)
    const hidden = cv.getHiddenColumns()
    expect(hidden.has('businessKey')).toBe(true)
    expect(hidden.has('startTime')).toBe(true)
    expect(hidden.size).toBe(2)
  })

  it('resetColumnVisibility clears back to the empty-set default', async () => {
    stubStorage()
    const cv = await freshColumnVisibility()
    cv.setColumnHidden('businessKey', true)
    cv.setColumnHidden('startTime', true)
    cv.resetColumnVisibility()
    expect(cv.getHiddenColumns().size).toBe(0)
  })

  it('persists the choice and reads it back on the next boot', async () => {
    const store = stubStorage()
    const first = await freshColumnVisibility()
    first.setColumnHidden('businessKey', true)
    expect(store.get('inspector.resultsGridHiddenColumns')).toBe('["businessKey"]')
    const second = await freshColumnVisibility() // fresh module = fresh browser session
    expect(second.getHiddenColumns().has('businessKey')).toBe(true)
  })

  it('falls back to the empty set on a corrupt stored value', async () => {
    stubStorage({ 'inspector.resultsGridHiddenColumns': 'not json' })
    const cv = await freshColumnVisibility()
    expect(cv.getHiddenColumns().size).toBe(0)
  })

  it('falls back to the empty set when the stored value is not an array', async () => {
    stubStorage({ 'inspector.resultsGridHiddenColumns': '{"businessKey":true}' })
    const cv = await freshColumnVisibility()
    expect(cv.getHiddenColumns().size).toBe(0)
  })

  it('survives an unavailable localStorage (private mode) — in-memory still works', async () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    })
    const cv = await freshColumnVisibility()
    expect(cv.getHiddenColumns().size).toBe(0)
    expect(() => {
      cv.setColumnHidden('businessKey', true)
    }).not.toThrow()
    expect(cv.getHiddenColumns().has('businessKey')).toBe(true)
  })

  it('notifies subscribers on change and stops after unsubscribe', async () => {
    stubStorage()
    const cv = await freshColumnVisibility()
    const listener = vi.fn()
    const unsubscribe = cv.subscribeHiddenColumns(listener)
    cv.setColumnHidden('businessKey', true)
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
    cv.setColumnHidden('startTime', true)
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('resetColumnVisibility also notifies subscribers', async () => {
    stubStorage()
    const cv = await freshColumnVisibility()
    cv.setColumnHidden('businessKey', true)
    const listener = vi.fn()
    cv.subscribeHiddenColumns(listener)
    cv.resetColumnVisibility()
    expect(listener).toHaveBeenCalledTimes(1)
    expect(cv.getHiddenColumns().size).toBe(0)
  })

  it('setHiddenColumns (#197) replaces the whole set in one call, persists, and notifies', async () => {
    const store = stubStorage()
    const cv = await freshColumnVisibility()
    cv.setColumnHidden('businessKey', true)
    const listener = vi.fn()
    cv.subscribeHiddenColumns(listener)
    cv.setHiddenColumns(new Set(['startTime', 'currentActivityOrError']))
    const hidden = cv.getHiddenColumns()
    expect(hidden.has('businessKey')).toBe(false) // fully replaced, not merged
    expect(hidden.has('startTime')).toBe(true)
    expect(hidden.has('currentActivityOrError')).toBe(true)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(store.get('inspector.resultsGridHiddenColumns')).toContain('startTime')
  })
})

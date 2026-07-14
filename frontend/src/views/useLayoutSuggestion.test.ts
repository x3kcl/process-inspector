// @vitest-environment jsdom
// #197 (docs/SHARED-VIEWS.md §8): the confirm-prompt precedence logic — the load-bearing
// behaviors are "suggest only when it actually differs," "never re-ask once answered," and
// "a manual edit counts as an implicit dismiss." This is exactly the design the adversarial
// review (Copilot + Gemini) forced a rewrite of — the FIRST draft ("viewer's own choice
// always silently wins forever") made shared-view layout a dead-on-arrival feature.
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function stubStorage(): Map<string, string> {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, value)
    },
  })
  return store
}

/** Fresh module graph per test — columnVisibility.ts and viewLayoutDecisions.ts are both
 *  lazy-hydrated module-level stores, same rationale as columnVisibility.test.ts. */
async function freshHook(): Promise<typeof import('./useLayoutSuggestion')> {
  vi.resetModules()
  return import('./useLayoutSuggestion')
}

beforeEach(() => {
  stubStorage()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('useLayoutSuggestion (#197)', () => {
  it('suggests nothing when the URL carries no cols param', async () => {
    const { useLayoutSuggestion } = await freshHook()
    const { result } = renderHook(() => useLayoutSuggestion('status=FAILED'))
    expect(result.current.cols).toBeNull()
  })

  it('suggests nothing when the suggestion already matches the current columns', async () => {
    const { useLayoutSuggestion } = await freshHook()
    // Imported AFTER freshHook's resetModules(), so this resolves to the SAME module
    // instance useLayoutSuggestion.ts itself imports internally.
    const cv = await import('../lib/columnVisibility')
    cv.setColumnHidden('startTime', true)
    const { result } = renderHook(() => useLayoutSuggestion('status=FAILED&cols=startTime'))
    expect(result.current.cols).toBeNull()
  })

  it('surfaces a pending suggestion when it differs from the current columns', async () => {
    const { useLayoutSuggestion } = await freshHook()
    const { result } = renderHook(() =>
      useLayoutSuggestion('status=FAILED&cols=startTime,businessKey'),
    )
    expect(result.current.cols).toEqual(new Set(['startTime', 'businessKey']))
  })

  it('unknown column ids in the URL are dropped, never applied blindly', async () => {
    const { useLayoutSuggestion } = await freshHook()
    const { result } = renderHook(() =>
      useLayoutSuggestion('status=FAILED&cols=startTime,totallyBogusColumn'),
    )
    expect(result.current.cols).toEqual(new Set(['startTime']))
  })

  it('apply() adopts the suggestion as the new global default and clears the prompt', async () => {
    const { useLayoutSuggestion } = await freshHook()
    const cv = await import('../lib/columnVisibility')
    const { result, rerender } = renderHook(({ paramsKey }) => useLayoutSuggestion(paramsKey), {
      initialProps: { paramsKey: 'status=FAILED&cols=startTime' },
    })
    expect(result.current.cols).toEqual(new Set(['startTime']))
    act(() => {
      result.current.apply()
    })
    expect(cv.getHiddenColumns()).toEqual(new Set(['startTime']))
    rerender({ paramsKey: 'status=FAILED&cols=startTime' })
    expect(result.current.cols).toBeNull() // matches now, nothing left to suggest
  })

  it('dismiss() clears the prompt without touching the global columns', async () => {
    const { useLayoutSuggestion } = await freshHook()
    const cv = await import('../lib/columnVisibility')
    const { result } = renderHook(() => useLayoutSuggestion('status=FAILED&cols=startTime'))
    act(() => {
      result.current.dismiss()
    })
    expect(result.current.cols).toBeNull()
    expect(cv.getHiddenColumns().size).toBe(0)
  })

  it('a dismissed suggestion is never re-asked for the identical (view, suggestion) pair', async () => {
    const { useLayoutSuggestion } = await freshHook()
    const first = renderHook(() => useLayoutSuggestion('status=FAILED&cols=startTime'))
    act(() => {
      first.result.current.dismiss()
    })
    first.unmount()
    // A brand-new mount (e.g. navigating away and back) — the decision persisted to storage.
    const second = renderHook(() => useLayoutSuggestion('status=FAILED&cols=startTime'))
    expect(second.result.current.cols).toBeNull()
  })

  it('a DIFFERENT suggested layout for the same search is asked fresh, even after a prior dismissal', async () => {
    const { useLayoutSuggestion } = await freshHook()
    const first = renderHook(() => useLayoutSuggestion('status=FAILED&cols=startTime'))
    act(() => {
      first.result.current.dismiss()
    })
    first.unmount()
    const second = renderHook(() => useLayoutSuggestion('status=FAILED&cols=businessKey'))
    expect(second.result.current.cols).toEqual(new Set(['businessKey']))
  })

  it('manually changing columns while a suggestion is pending counts as an implicit dismiss', async () => {
    const { useLayoutSuggestion } = await freshHook()
    const cv = await import('../lib/columnVisibility')
    const { result } = renderHook(() => useLayoutSuggestion('status=FAILED&cols=startTime'))
    expect(result.current.cols).toEqual(new Set(['startTime']))
    act(() => {
      cv.setColumnHidden('businessKey', true) // the viewer makes their OWN choice mid-prompt
    })
    expect(result.current.cols).toBeNull() // the banner must not still be showing
    // The decision persists — reopening the identical view later does not re-ask.
    const reopened = renderHook(() => useLayoutSuggestion('status=FAILED&cols=startTime'))
    expect(reopened.result.current.cols).toBeNull()
  })
})

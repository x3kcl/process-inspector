// @vitest-environment jsdom
// R-UXQ-02 (usability W1#5): after SPA navigation, focus must never be left on <body> —
// it lands on the new route's main heading, or the nearest surviving landmark.
import { afterEach, describe, expect, it } from 'vitest'
import { restoreRouteFocus } from './routeFocus'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('restoreRouteFocus', () => {
  it('moves focus from <body> to the main heading', () => {
    document.body.innerHTML = '<div class="app"><main><h2>Operations log</h2></main></div>'
    const target = restoreRouteFocus()
    expect(target?.tagName).toBe('H2')
    expect(document.activeElement).toBe(target)
  })

  it('falls back to <main> when the route has no heading (inspect vitals)', () => {
    document.body.innerHTML = '<div class="app"><main class="inspect"><p>vitals</p></main></div>'
    const target = restoreRouteFocus()
    expect(target?.tagName).toBe('MAIN')
    expect(document.activeElement).toBe(target)
  })

  it('falls back to the app container while a lazy route is still loading', () => {
    document.body.innerHTML = '<div class="app"><p>Loading case…</p></div>'
    const target = restoreRouteFocus()
    expect(target?.className).toBe('app')
    expect(document.activeElement).toBe(target)
  })

  it('never steals focus from a surviving element (e.g. the topbar link just activated)', () => {
    document.body.innerHTML =
      '<div class="app"><a href="/audit" id="link">Ops log</a><main><h2>Ops</h2></main></div>'
    const link = document.getElementById('link') as HTMLElement
    link.focus()
    expect(restoreRouteFocus()).toBeNull()
    expect(document.activeElement).toBe(link)
  })

  it('makes the target programmatically focusable without touching authored tabindex', () => {
    document.body.innerHTML = '<div class="app"><main><h2 tabindex="0">Kept</h2></main></div>'
    const target = restoreRouteFocus()
    expect(target?.getAttribute('tabindex')).toBe('0')
    document.body.innerHTML = '<div class="app"><main><h2>Search</h2></main></div>'
    ;(document.activeElement as HTMLElement | null)?.blur()
    const fresh = restoreRouteFocus()
    expect(fresh?.getAttribute('tabindex')).toBe('-1')
  })
})

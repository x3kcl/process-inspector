// @vitest-environment jsdom
// R-UXQ-08 (#104 slice 2b): the persisted theme control — a three-option segmented control,
// never a bare icon toggle; the active option is aria-pressed. Mirrors ZoneToggle.test.ts.
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import { setThemePreference } from '../lib/theme'
import { ThemeToggle } from './ThemeToggle'

afterEach(() => {
  setThemePreference('system')
})

describe('<ThemeToggle>', () => {
  it('offers System, Light and Dark, with System pressed by default', () => {
    const html = renderToStaticMarkup(createElement(ThemeToggle))
    expect(html).toContain('System')
    expect(html).toContain('Light')
    expect(html).toContain('Dark')
    const systemButton = html.split('<button').find((chunk) => chunk.includes('>System<'))
    expect(systemButton).toContain('aria-pressed="true"')
  })

  it('marks Dark pressed once toggled', () => {
    setThemePreference('dark')
    const html = renderToStaticMarkup(createElement(ThemeToggle))
    const darkButton = html.split('<button').find((chunk) => chunk.includes('>Dark<'))
    expect(darkButton).toContain('aria-pressed="true"')
  })
})

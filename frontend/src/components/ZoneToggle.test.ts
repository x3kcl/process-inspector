// R-UXQ-03: the one-click persisted display-zone control (SPEC §10a) — a two-option
// segmented control, never a bare icon toggle; the active option is aria-pressed.
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import { setDisplayZone } from '../lib/format'
import { ZoneToggle } from './ZoneToggle'

afterEach(() => {
  setDisplayZone('local')
})

describe('<ZoneToggle>', () => {
  it('offers exactly Local and UTC, with Local pressed by default', () => {
    const html = renderToStaticMarkup(createElement(ZoneToggle))
    expect(html).toContain('UTC')
    expect(html).toMatch(/aria-pressed="true"[^>]*>Local|Local[^<]*<[^>]*aria-pressed="true"/)
  })

  it('marks UTC pressed once toggled', () => {
    setDisplayZone('utc')
    const html = renderToStaticMarkup(createElement(ZoneToggle))
    const utcButton = html.split('<button').find((chunk) => chunk.includes('>UTC<'))
    expect(utcButton).toContain('aria-pressed="true"')
  })
})

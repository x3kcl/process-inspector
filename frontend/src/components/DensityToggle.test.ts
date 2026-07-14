// @vitest-environment jsdom
// R-UXQ-09 (#104 slice 3/6): the persisted density control — a two-option segmented control,
// never a bare icon toggle; the active option is aria-pressed. Mirrors ThemeToggle.test.ts.
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import { setDensity } from '../lib/density'
import { DensityToggle } from './DensityToggle'

afterEach(() => {
  setDensity('comfortable')
})

describe('<DensityToggle>', () => {
  it('offers Comfortable and Compact, with Comfortable pressed by default', () => {
    const html = renderToStaticMarkup(createElement(DensityToggle))
    expect(html).toContain('Comfortable')
    expect(html).toContain('Compact')
    const comfortableButton = html.split('<button').find((chunk) => chunk.includes('>Comfortable<'))
    expect(comfortableButton).toContain('aria-pressed="true"')
  })

  it('marks Compact pressed once toggled', () => {
    setDensity('compact')
    const html = renderToStaticMarkup(createElement(DensityToggle))
    const compactButton = html.split('<button').find((chunk) => chunk.includes('>Compact<'))
    expect(compactButton).toContain('aria-pressed="true"')
  })
})

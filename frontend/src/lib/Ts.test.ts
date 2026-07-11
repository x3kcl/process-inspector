// R-UXQ-03: the <Ts> element — every human timestamp renders as a <time> carrying the
// full UTC ISO-8601 in title + aria, so the hover/screen-reader answer is always
// unambiguous for a colleague in another timezone (M9 task 3).
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import { setDisplayZone } from './format'
import { Ts } from './Ts'

afterEach(() => {
  setDisplayZone('local')
})

const ISO = '2026-07-10T14:00:00+02:00'
const UTC = '2026-07-10T12:00:00.000Z'

describe('<Ts>', () => {
  it('renders a <time> with the normalized UTC ISO in dateTime, title and aria-label', () => {
    const html = renderToStaticMarkup(createElement(Ts, { iso: ISO }))
    expect(html).toContain('<time')
    expect(html.toLowerCase()).toContain(`datetime="${UTC.toLowerCase()}"`)
    expect(html).toContain(`title="${UTC}"`)
    expect(html).toMatch(new RegExp(`aria-label="[^"]*${UTC.replaceAll('.', '\\.')}[^"]*"`))
  })

  it('appends a relative age when asked', () => {
    const nowMs = Date.parse('2026-07-10T15:20:00Z')
    const html = renderToStaticMarkup(createElement(Ts, { iso: ISO, relative: true, nowMs }))
    expect(html).toContain('3h 20m ago')
  })

  it('omits the relative age by default', () => {
    const html = renderToStaticMarkup(createElement(Ts, { iso: ISO }))
    expect(html).not.toContain('ago')
  })

  it('renders nothing for a missing stamp', () => {
    expect(renderToStaticMarkup(createElement(Ts, { iso: undefined }))).toBe('')
    expect(renderToStaticMarkup(createElement(Ts, { iso: null }))).toBe('')
    expect(renderToStaticMarkup(createElement(Ts, { iso: '' }))).toBe('')
  })

  it('follows the one-click UTC toggle', () => {
    setDisplayZone('utc')
    const html = renderToStaticMarkup(createElement(Ts, { iso: ISO }))
    expect(html).toContain('UTC')
  })

  it('offers copy-as-ISO when asked — the copied text is the UTC ISO form', () => {
    const html = renderToStaticMarkup(createElement(Ts, { iso: ISO, copyIso: true }))
    expect(html).toContain('copy ISO')
  })

  it('omits the copy affordance by default', () => {
    const html = renderToStaticMarkup(createElement(Ts, { iso: ISO }))
    expect(html).not.toContain('copy ISO')
  })

  it('passes className through to the <time> element', () => {
    const html = renderToStaticMarkup(createElement(Ts, { iso: ISO, className: 'recent-at' }))
    expect(html).toContain('class="recent-at"')
  })
})

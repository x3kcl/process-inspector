// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { DiagramLegend } from './DiagramLegend'
import { MARKER_KINDS, presentKinds } from './markerKinds'

afterEach(cleanup)

describe('presentKinds', () => {
  it('keeps only the kinds with ids, in table order', () => {
    const kinds = presentKinds({
      siblingOnlyActivityIds: ['b'],
      activeActivityIds: ['a'],
      deadLetterActivityIds: [],
      pickerTargetIds: undefined,
    })
    expect(kinds.map((kind) => kind.key)).toEqual(['activeActivityIds', 'siblingOnlyActivityIds'])
  })

  it('is empty for an empty marker set', () => {
    expect(presentKinds({ activeActivityIds: [], deadLetterActivityIds: [] })).toEqual([])
  })

  it('gives every glyph-bearing kind an accessible overlay label', () => {
    for (const kind of MARKER_KINDS) expect(kind.label.length).toBeGreaterThan(0)
  })
})

describe('DiagramLegend', () => {
  it('renders nothing without kinds', () => {
    const { container } = render(<DiagramLegend kinds={[]} />)
    expect(container.innerHTML).toBe('')
  })

  it('lists each kind with its glyph and words, glyph hidden from assistive tech', () => {
    render(<DiagramLegend kinds={presentKinds({ deadLetterActivityIds: ['x'] })} />)
    const legend = screen.getByRole('list', { name: 'diagram legend' })
    const item = within(legend).getByRole('listitem')
    expect(item.textContent).toBe('⚠ dead-letter job')
    expect(item.querySelector('.dlq-overlay')?.getAttribute('aria-hidden')).toBe('true')
    expect(item.querySelector('rect')?.style.strokeWidth).toBe('2')
  })
})

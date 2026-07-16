// @vitest-environment jsdom
// R-UXQ-02 (usability W1#5): the detail tablist must follow the ARIA APG tabs pattern —
// roving tabindex, ArrowLeft/ArrowRight (wrapping) + Home/End move focus, activation
// stays manual (Enter/Space/click), so a focus pass never triggers the lazy tab fetches.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DetailTabBar } from './DetailTabBar'
import { TAB_IDS, TAB_LABELS } from './tabs'

afterEach(cleanup)

function tab(name: string): HTMLElement {
  return screen.getByRole('tab', { name })
}

describe('DetailTabBar roving focus (ARIA APG)', () => {
  it('gives the active tab tabIndex 0 and every other tab -1', () => {
    render(<DetailTabBar active="errors-jobs" onSelect={vi.fn()} />)
    expect(tab('Errors & Jobs').tabIndex).toBe(0)
    for (const id of TAB_IDS.filter((candidate) => candidate !== 'errors-jobs')) {
      expect(tab(TAB_LABELS[id]).tabIndex).toBe(-1)
    }
  })

  it('ArrowRight moves focus to the next tab WITHOUT activating it', () => {
    const onSelect = vi.fn()
    render(<DetailTabBar active="variables" onSelect={onSelect} />)
    const first = tab('Variables')
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(tab('Errors & Jobs'))
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('ArrowLeft on the first tab wraps to the last', () => {
    render(<DetailTabBar active="variables" onSelect={vi.fn()} />)
    const first = tab('Variables')
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(tab(TAB_LABELS[TAB_IDS[TAB_IDS.length - 1]]))
  })

  it('ArrowRight on the last tab wraps to the first', () => {
    render(<DetailTabBar active="variables" onSelect={vi.fn()} />)
    const last = tab(TAB_LABELS[TAB_IDS[TAB_IDS.length - 1]])
    last.focus()
    fireEvent.keyDown(last, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(tab('Variables'))
  })

  it('Home and End jump to the first and last tab', () => {
    render(<DetailTabBar active="timeline" onSelect={vi.fn()} />)
    const start = tab('Timeline')
    start.focus()
    fireEvent.keyDown(start, { key: 'Home' })
    expect(document.activeElement).toBe(tab('Variables'))
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'End' })
    expect(document.activeElement).toBe(tab(TAB_LABELS[TAB_IDS[TAB_IDS.length - 1]]))
  })

  it('click activates a tab (selection is manual, not focus-driven)', () => {
    const onSelect = vi.fn()
    render(<DetailTabBar active="variables" onSelect={onSelect} />)
    fireEvent.click(tab('Timeline'))
    expect(onSelect).toHaveBeenCalledWith('timeline')
  })

  it('keeps the tablist/tab ARIA contract (axe-critical structure)', () => {
    render(<DetailTabBar active="variables" onSelect={vi.fn()} />)
    const tablist = screen.getByRole('tablist')
    expect(tablist.getAttribute('aria-label')).toBe('Instance detail tabs')
    expect(tab('Variables').getAttribute('aria-selected')).toBe('true')
    expect(tab('Timeline').getAttribute('aria-selected')).toBe('false')
  })

  it('shows a visible hint for the manual-activation pattern (#211)', () => {
    render(<DetailTabBar active="variables" onSelect={vi.fn()} />)
    const hint = screen.getByText(/moves between tabs/i)
    expect(hint.textContent).toMatch(/Enter/)
  })
})

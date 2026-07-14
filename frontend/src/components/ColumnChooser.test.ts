// @vitest-environment jsdom
// R-UXQ-09 (#104 slice 4/6): the results-grid column-visibility chooser — a custom dropdown
// (AG Grid Community has no built-in column tool panel, ADR-002) of real checkboxes, one per
// HIDEABLE column, plus a reset action. Locked columns (open/protected/engineName/status)
// must never appear as togglable rows here. Assertions use raw DOM properties (checked,
// getAttribute, disabled) rather than jest-dom matchers — this codebase has no jest-dom
// dependency (see StatusChip.test.tsx / HeaderStrip.test.tsx for the same convention).
import { createElement } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { resetColumnVisibility, setColumnHidden } from '../lib/columnVisibility'
import { ColumnChooser } from './ColumnChooser'

afterEach(() => {
  cleanup()
  resetColumnVisibility()
})

function openChooser() {
  render(createElement(ColumnChooser))
  fireEvent.click(screen.getByRole('button', { name: 'Columns ▾' }))
}

/** Real `instanceof` narrowing (not a type assertion) — testing-library's queries are typed
 * to return the general HTMLElement, and this codebase avoids `as` casts wherever a runtime
 * check can do the same job honestly. */
function checkboxByLabel(label: string): HTMLInputElement {
  const el = screen.getByLabelText(label)
  if (!(el instanceof HTMLInputElement)) throw new Error(`"${label}" is not a checkbox`)
  return el
}

function buttonByName(name: string): HTMLButtonElement {
  const el = screen.getByRole('button', { name })
  if (!(el instanceof HTMLButtonElement)) throw new Error(`"${name}" is not a button`)
  return el
}

describe('<ColumnChooser>', () => {
  it('the toggle button starts collapsed (aria-expanded false)', () => {
    render(createElement(ColumnChooser))
    expect(buttonByName('Columns ▾').getAttribute('aria-expanded')).toBe('false')
  })

  it('opening the panel lists exactly the 6 hideable columns, all checked by default', () => {
    openChooser()
    const expected = [
      'Process ID',
      'Business Key',
      'Definition',
      'Start Time',
      'Failure Time',
      'Current Activity / Error',
    ]
    for (const label of expected) {
      expect(checkboxByLabel(label).checked).toBe(true)
    }
    expect(screen.getAllByRole('checkbox')).toHaveLength(expected.length)
  })

  it('never offers the locked columns as checkboxes', () => {
    openChooser()
    for (const locked of ['Open', 'Protected', 'Engine', 'Status']) {
      expect(screen.queryByLabelText(locked)).toBeNull()
    }
  })

  it('explains why the locked columns are missing', () => {
    openChooser()
    expect(screen.getByText(/always shown/)).toBeTruthy()
  })

  it('unchecking a column hides it via the store', () => {
    openChooser()
    fireEvent.click(checkboxByLabel('Business Key'))
    expect(checkboxByLabel('Business Key').checked).toBe(false)
  })

  it('re-checking a hidden column shows it again', () => {
    setColumnHidden('businessKey', true)
    openChooser()
    const checkbox = checkboxByLabel('Business Key')
    expect(checkbox.checked).toBe(false)
    fireEvent.click(checkbox)
    expect(checkbox.checked).toBe(true)
  })

  it('Reset to default re-checks every column and is disabled when nothing is hidden', () => {
    openChooser()
    expect(buttonByName('Reset to default').disabled).toBe(true)
    fireEvent.click(checkboxByLabel('Business Key'))
    fireEvent.click(checkboxByLabel('Start Time'))
    const resetButton = buttonByName('Reset to default')
    expect(resetButton.disabled).toBe(false)
    fireEvent.click(resetButton)
    expect(checkboxByLabel('Business Key').checked).toBe(true)
    expect(checkboxByLabel('Start Time').checked).toBe(true)
  })

  it('Escape closes the panel', () => {
    openChooser()
    expect(screen.getByRole('region', { name: 'Choose visible columns' })).toBeTruthy()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('region', { name: 'Choose visible columns' })).toBeNull()
  })

  it('Escape restores focus to the toggle button, even from a checkbox inside the panel', () => {
    openChooser()
    const checkbox = checkboxByLabel('Business Key')
    checkbox.focus()
    expect(document.activeElement).toBe(checkbox)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(document.activeElement).toBe(buttonByName('Columns ▾'))
  })

  it('clicking outside the chooser closes the panel', () => {
    openChooser()
    expect(screen.getByRole('region', { name: 'Choose visible columns' })).toBeTruthy()
    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('region', { name: 'Choose visible columns' })).toBeNull()
  })

  it('clicking outside does NOT steal focus back to the toggle button', () => {
    openChooser()
    const outsideButton = document.createElement('button')
    document.body.appendChild(outsideButton)
    outsideButton.focus()
    fireEvent.mouseDown(outsideButton)
    expect(document.activeElement).toBe(outsideButton)
    outsideButton.remove()
  })
})

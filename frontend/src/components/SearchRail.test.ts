// @vitest-environment jsdom
// #118 item 3: the date/time filter split (two single-segment inputs, not one
// datetime-local — see SearchRail.tsx's DateTimeFilterField doc comment) needs its
// split/join round-trip proven, since a wrong combine would silently corrupt the search
// filter's time bounds.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { createElement } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SearchRequest } from '../api/model'
import {
  BLANK_SEARCH_HINT,
  BLANK_SEARCH_TITLE,
  localToIso,
  SearchRail,
  splitLocal,
} from './SearchRail'

describe('splitLocal', () => {
  it('splits an ISO instant into separate date and time strings', () => {
    const { date, time } = splitLocal('2026-07-09T14:30:00.000Z')
    expect(date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(time).toMatch(/^\d{2}:\d{2}$/)
  })

  it('returns empty strings for undefined (unset filter)', () => {
    expect(splitLocal(undefined)).toEqual({ date: '', time: '' })
  })

  it('returns empty strings for an unparseable value, never throws', () => {
    expect(splitLocal('not a date')).toEqual({ date: '', time: '' })
  })
})

describe('localToIso', () => {
  it('round-trips through splitLocal back to an equivalent instant', () => {
    const original = '2026-07-09T14:30:00.000Z'
    const roundTripped = localToIso(splitLocal(original))
    expect(new Date(roundTripped ?? '').getTime()).toBe(new Date(original).getTime())
  })

  it('is undefined when the date half is empty — an unset filter, not midnight', () => {
    expect(localToIso({ date: '', time: '09:00' })).toBeUndefined()
  })

  it('defaults the time to midnight when only a date was entered', () => {
    const iso = localToIso({ date: '2026-07-09', time: '' })
    expect(iso).toBeDefined()
    const parsed = new Date(iso ?? '')
    expect(parsed.getHours()).toBe(0)
    expect(parsed.getMinutes()).toBe(0)
  })

  it('is undefined for a malformed date/time combination', () => {
    expect(localToIso({ date: 'not-a-date', time: '09:00' })).toBeUndefined()
  })
})

afterEach(cleanup)

function renderRail(initial: SearchRequest | null, onSubmit: (request: SearchRequest) => void) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, enabled: false } },
  })
  return render(
    createElement(
      QueryClientProvider,
      { client },
      createElement(SearchRail, {
        engines: [],
        initial,
        currentSearch: null,
        response: undefined,
        busy: false,
        collapsed: false,
        onToggle: () => undefined,
        onSubmit,
      }),
    ),
  )
}

// #233: the rail is the URL's editor — a drill-link's version scope must survive a form
// re-submit (silently dropping it would reopen the wrong-target bug the drill fix closed).
describe('SearchRail definition-version round-trip (#233)', () => {
  it('re-submitting the form keeps the version filter from the URL', () => {
    const onSubmit = vi.fn()
    renderRail(
      { processDefinitionKey: 'payment', definitionVersion: 42, statuses: ['FAILED'] },
      onSubmit,
    )
    fireEvent.click(screen.getByRole('button', { name: /^Search/ }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    const submitted = onSubmit.mock.calls[0]?.[0] as SearchRequest
    expect(submitted.processDefinitionKey).toBe('payment')
    expect(submitted.definitionVersion).toBe(42)
  })

  it('a non-integer typed version blocks the submit at the input (native step validation)', () => {
    const onSubmit = vi.fn()
    renderRail({ processDefinitionKey: 'payment' }, onSubmit)
    fireEvent.change(screen.getByLabelText('Definition version'), { target: { value: '42.9' } })
    fireEvent.click(screen.getByRole('button', { name: /^Search/ }))
    // step=1 makes 42.9 a stepMismatch — constraint validation stops the form.
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('a scientific-notation version submits as its exact integer, never a parseInt mangle', () => {
    const onSubmit = vi.fn()
    renderRail({ processDefinitionKey: 'payment' }, onSubmit)
    // "4e2" passes the number input's own validation because it IS 400; parseInt would
    // have silently read it as v4, and dropping it would silently widen the scope.
    fireEvent.change(screen.getByLabelText('Definition version'), { target: { value: '4e2' } })
    fireEvent.click(screen.getByRole('button', { name: /^Search/ }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    const submitted = onSubmit.mock.calls[0]?.[0] as SearchRequest
    expect(submitted.definitionVersion).toBe(400)
  })

  it('clearing the version field submits without a version filter', () => {
    const onSubmit = vi.fn()
    renderRail({ processDefinitionKey: 'payment', definitionVersion: 42 }, onSubmit)
    fireEvent.change(screen.getByLabelText('Definition version'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: /^Search/ }))
    const submitted = onSubmit.mock.calls[0]?.[0] as SearchRequest
    expect(submitted.definitionVersion).toBeUndefined()
  })
})

// #246: an all-blank submit fires no request by design (empty encode → decodeSearch null →
// query disabled) — so the button must be disabled-with-reason, never a silent dead click.
describe('SearchRail blank-filter guard (#246)', () => {
  it('disables Search with the reason (title + visible hint) when every filter is blank', () => {
    const onSubmit = vi.fn()
    renderRail(null, onSubmit)
    const button = screen.getByRole('button', { name: 'Search' })
    expect(button).toHaveProperty('disabled', true)
    expect(button.getAttribute('title')).toBe(BLANK_SEARCH_TITLE)
    expect(button.getAttribute('aria-describedby')).toBe('blank-search-hint')
    expect(screen.getByText(BLANK_SEARCH_HINT)).toBeTruthy()
  })

  it('a programmatic submit of the blank form never reaches onSubmit (handler guard)', () => {
    const onSubmit = vi.fn()
    const { container } = renderRail(null, onSubmit)
    const form = container.querySelector('form')
    expect(form).not.toBeNull()
    if (form !== null) fireEvent.submit(form)
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('typing any single filter enables the button, and submitting then works', () => {
    const onSubmit = vi.fn()
    renderRail(null, onSubmit)
    fireEvent.change(screen.getByLabelText('Definition key'), { target: { value: 'payment' } })
    const button = screen.getByRole('button', { name: 'Search' })
    expect(button).toHaveProperty('disabled', false)
    expect(button.getAttribute('title')).toBeNull()
    expect(screen.queryByText(BLANK_SEARCH_HINT)).toBeNull()
    fireEvent.click(button)
    expect(onSubmit).toHaveBeenCalledTimes(1)
    const submitted = onSubmit.mock.calls[0]?.[0] as SearchRequest
    expect(submitted.processDefinitionKey).toBe('payment')
  })

  it('a status tick alone counts as a filter', () => {
    const onSubmit = vi.fn()
    renderRail(null, onSubmit)
    fireEvent.click(screen.getByLabelText(/^Active/))
    expect(screen.getByRole('button', { name: 'Search' })).toHaveProperty('disabled', false)
  })

  it('clearing the only filter re-disables the button', () => {
    const onSubmit = vi.fn()
    renderRail({ errorText: 'SocketTimeout' }, onSubmit)
    expect(screen.getByRole('button', { name: 'Search' })).toHaveProperty('disabled', false)
    fireEvent.change(screen.getByLabelText('Error text'), { target: { value: '' } })
    expect(screen.getByRole('button', { name: 'Search' })).toHaveProperty('disabled', true)
  })

  it('a non-default sort alone keeps the button live — it fires a real search today', () => {
    // Blankness is defined by the URL codec (hasSearch over the encoded request), not an
    // ad-hoc field list: sortBy=failureTime alone encodes a param and DOES run a search.
    const onSubmit = vi.fn()
    renderRail({ sortBy: 'failureTime' }, onSubmit)
    expect(screen.getByRole('button', { name: 'Search' })).toHaveProperty('disabled', false)
  })
})

import { describe, expect, it } from 'vitest'
import { ticketHref } from './ticket'

describe('ticketHref', () => {
  const template = 'https://jira.example/browse/{ticketId}'

  it('substitutes and URL-encodes the ticketId', () => {
    expect(ticketHref(template, 'OPS-42')).toBe('https://jira.example/browse/OPS-42')
    expect(ticketHref(template, 'A B/C')).toBe('https://jira.example/browse/A%20B%2FC')
  })

  it('returns null when there is no template or no ticketId', () => {
    expect(ticketHref(null, 'OPS-42')).toBeNull()
    expect(ticketHref('', 'OPS-42')).toBeNull()
    expect(ticketHref(template, null)).toBeNull()
    expect(ticketHref(template, '')).toBeNull()
  })

  it('refuses a template that does not resolve to an http(s) URL', () => {
    expect(ticketHref('javascript:alert({ticketId})', 'OPS-42')).toBeNull()
    expect(ticketHref('/browse/{ticketId}', 'OPS-42')).toBeNull()
  })
})
